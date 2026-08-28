package io.github.lemcoder.koinference.benchmark.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.backend.BackendUnsupportedException
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.benchmark.app.IBackendService
import io.github.lemcoder.koinference.benchmark.app.IBenchmarkCallback
import io.github.lemcoder.koinference.benchmark.app.IGenerationCallback
import io.github.lemcoder.koinference.benchmark.app.IStatusCallback
import io.github.lemcoder.koinference.benchmark.config.BenchmarkArguments
import io.github.lemcoder.koinference.benchmark.platform.BenchmarkContext
import io.github.lemcoder.koinference.benchmark.prompts.PromptCorpus
import io.github.lemcoder.koinference.benchmark.result.toJson
import io.github.lemcoder.koinference.benchmark.runner.BenchmarkRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One engine, alone in its own process, reachable over binder.
 *
 * Subclassed once per backend rather than parameterised by an intent extra, because the process a
 * service runs in is fixed in the manifest and there is one manifest entry per process. The
 * subclass supplies the [Backend]; everything else is here.
 */
abstract class BackendService : Service() {

    protected abstract val backend: Backend

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var served: ServedModel? = null

    override fun onCreate() {
        super.onCreate()
        // The harness's Android probe reads device facts, PSS and thermal state through this.
        // Without it a results file would describe a device with every field null.
        BenchmarkContext.applicationContext = applicationContext
        startForegroundNotification()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        scope.launch { served?.unload() }
        scope.cancel()
        super.onDestroy()
    }

    private val binder = object : IBackendService.Stub() {

        override fun backendId(): String = backend.id

        /**
         * Probes by building a loader, which is as far as the check needs to go: the Android leg
         * refuses inside `platformBridge()`, before any weights are read.
         */
        override fun unsupportedReason(): String? = try {
            backend.loader(ModelConfig())
            null
        } catch (refusal: BackendUnsupportedException) {
            refusal.reason
        }

        override fun modelPaths(): List<String> =
            ModelFiles.forBackend(this@BackendService, backend).map { it.absolutePath }

        override fun runBenchmark(modelPath: String, configJson: String, callback: IBenchmarkCallback) {
            scope.launch {
                try {
                    val corpus = PromptCorpus.parse(assets.open(PROMPTS_ASSET).bufferedReader().readText())
                    val config = BenchmarkArguments.toConfig(
                        arguments = optionsOf(configJson) + mapOf(
                            "engine" to backend.id,
                            "model" to modelPath,
                            // This process's own cache: a model may sit on /data/local/tmp, which
                            // is readable but not writable, and LiteRT-LM will not run a large
                            // model without somewhere to put its weight cache.
                            "cacheDir" to cacheDir.absolutePath,
                        ),
                        corpusPromptIds = corpus.prompts.map { it.id },
                        runIdFallback = "run-${System.currentTimeMillis()}",
                    )

                    val file = BenchmarkRunner(
                        config = config,
                        prompts = corpus,
                        log = { line -> runCatching { callback.onProgress("${backend.id}: $line") } },
                    ).run()

                    callback.onFinished(file.toJson())
                } catch (failure: Throwable) {
                    log("benchmark failed", failure)
                    runCatching { callback.onFailed(describe(failure)) }
                }
            }
        }

        override fun load(modelPath: String, optionsJson: String, callback: IStatusCallback) {
            scope.launch {
                try {
                    served?.unload()
                    val model = ServedModel.load(backend, modelPath, optionsOf(optionsJson), cacheDir.absolutePath)
                    served = model
                    callback.onReady(model.describe())
                } catch (failure: Throwable) {
                    log("load failed", failure)
                    served = null
                    runCatching { callback.onFailed(describe(failure)) }
                }
            }
        }

        override fun generate(requestJson: String, callback: IGenerationCallback) {
            scope.launch {
                val model = served
                if (model == null) {
                    runCatching { callback.onFailed("no model loaded on ${backend.id}") }
                    return@launch
                }
                try {
                    model.generate(requestJson, callback)
                } catch (failure: Throwable) {
                    log("generate failed", failure)
                    runCatching { callback.onFailed(describe(failure)) }
                }
            }
        }

        override fun processMemory(): String {
            val info = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
            val runtime = Runtime.getRuntime()
            return buildJsonObject {
                put("pid", android.os.Process.myPid())
                put("pssKb", info.totalPss.toLong())
                put("privateDirtyKb", info.totalPrivateDirty.toLong())
                put("nativeHeapKb", android.os.Debug.getNativeHeapAllocatedSize() / 1024)
                put("javaHeapKb", (runtime.totalMemory() - runtime.freeMemory()) / 1024)
            }.toString()
        }

        override fun unload(callback: IStatusCallback) {
            scope.launch {
                runCatching { served?.unload() }
                served = null
                runCatching { callback.onReady("{}") }
            }
        }
    }

    /** Flat string map, because that is what [BenchmarkArguments] parses. */
    private fun optionsOf(json: String): Map<String, String> = runCatching {
        Json.parseToJsonElement(json).jsonObject
            .mapValues { (_, value) -> (value as? JsonPrimitive)?.content ?: value.toString() }
    }.getOrDefault(emptyMap())

    private fun describe(failure: Throwable): String =
        "${failure::class.java.simpleName}: ${failure.message ?: "no message"}"

    private fun log(message: String, failure: Throwable? = null) {
        Log.w("koinference-${backend.id}", message, failure)
    }

    /**
     * Foreground, because Android stops background services and a suite that dies at minute eleven
     * of a twenty-minute run has measured nothing.
     */
    private fun startForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Inference engines", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(
            backend.id.hashCode() and 0xffff,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("koinference")
                .setContentText("${backend.id} engine process")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .build(),
        )
    }

    private companion object {
        const val CHANNEL_ID = "koinference-engines"
        const val PROMPTS_ASSET = "prompts.json"
    }
}
