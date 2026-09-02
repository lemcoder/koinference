package io.github.lemcoder.koinference.benchmark.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import io.github.lemcoder.koinference.benchmark.app.client.BackendConnection
import io.github.lemcoder.koinference.benchmark.app.client.BackendProcess
import io.github.lemcoder.koinference.benchmark.app.client.BenchmarkSession
import io.github.lemcoder.koinference.benchmark.app.ui.ResultsTable
import io.github.lemcoder.koinference.benchmark.config.BenchmarkArguments
import io.github.lemcoder.koinference.benchmark.engine.availableEngines
import io.github.lemcoder.koinference.benchmark.prompts.PromptCorpus
import io.github.lemcoder.koinference.benchmark.result.toJson
import io.github.lemcoder.koinference.benchmark.runner.BenchmarkRunner
import io.github.lemcoder.koinference.benchmark.platform.BenchmarkContext
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The benchmark, driven from a shell instead of a screen.
 *
 * The UI is fine for one look at one device; a matrix is a script. This runs the same
 * [BenchmarkSession] the screen does, writes the merged results file, and logs one line per record
 * so `adb logcat` shows the numbers without pulling anything:
 *
 *     adb shell am start-foreground-service \
 *       -n io.github.lemcoder.koinference.benchmark.app/.service.BenchmarkService \
 *       --es engines all \
 *       --es model /data/local/tmp/koinference/LFM2.5-1.2B-Instruct-Q4_0.gguf \
 *       --es promptSet short_generation_v1 \
 *       --ei iterations 3 --ei warmup 1 --ei maxNewTokens 32
 *
 *     adb logcat -s koinference-benchmark:I
 *
 * `engines` takes ids or labels, comma separated, or `all`. `model` is optional and applies to every
 * engine that can read that container — one GGUF, two GGUF engines — and an engine that cannot read
 * it is skipped by name rather than silently. Everything else is passed to the harness untouched, so
 * `threads`, `gpu`, `maxContextTokens` and the rest mean what they mean there.
 */
class BenchmarkService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        BenchmarkContext.applicationContext = applicationContext
        startForegroundNotification()

        val connections = BackendProcess.entries.associateWith {
            BackendConnection(applicationContext, it.serviceClass)
        }
        val session = BenchmarkSession(applicationContext, connections)

        val processes = processesFrom(intent.getStringExtra(EXTRA_ENGINES))
        if (processes.isEmpty()) {
            log("no engine matched '${intent.getStringExtra(EXTRA_ENGINES)}'; known: ${BackendProcess.entries.map { it.label }}")
            stopSelf()
            return START_NOT_STICKY
        }

        val modelPath = intent.getStringExtra(EXTRA_MODEL)
        val options = optionsFrom(intent)
        val outPath = intent.getStringExtra(EXTRA_OUT)
            ?: File(getExternalFilesDir(null), DEFAULT_OUT).absolutePath

        // Android puts a service process that hosts no UI in /foreground, which is capped at cpus
        // 0-7; only the process holding the visible Activity gets /top-app and the X3 prime core.
        // This runs the engine here, in that process, to find out whether the prime core is worth
        // the isolation it costs — the memory figures from such a run describe this process, Compose
        // and all, so they are not comparable with a normal one.
        if (intent.getBooleanExtra(EXTRA_IN_PROCESS, false)) {
            runInProcess(processes.first(), modelPath, options, outPath)
            return START_NOT_STICKY
        }

        scope.launch {
            try {
                val (targets, skipped) = session.resolveTargets(processes, modelPath)
                skipped.forEach { log("skipped $it") }
                if (targets.isEmpty()) {
                    log("nothing to run. Models on this device: ${session.discovered()}")
                    stopSelf()
                    return@launch
                }

                log("running ${targets.map { it.first.label }} with $options")
                val outcomes = session.run(targets, options) { line -> log(line) }

                outcomes.filter { it.failure != null }.forEach { log("FAILED ${it.process.label}: ${it.failure}") }

                val results = outcomes.mapNotNull { it.resultsJson }
                ResultsTable.rows(results).forEach { row ->
                    // One line per record, so a scripted run needs no file at all to be read.
                    log(
                        "RESULT ${row.engineId} ${row.workload} " +
                            "tok/s=${row.tokensPerSecond.format()} ttft=${row.ttftMs.format()}ms " +
                            "tokens=${row.tokens} chunks=${row.chunks} " +
                            "peakPss=${row.peakPssMb.format()}MB afterLoad=${row.weightsPssMb.format()}MB " +
                            "afterRun=${row.afterRunPssMb.format()}MB${row.note?.let { " note=$it" } ?: ""}",
                    )
                }

                writeResults(outPath, results)
            } catch (failure: Throwable) {
                log("run failed: ${failure::class.java.simpleName}: ${failure.message}")
            } finally {
                connections.values.forEach { it.stopService() }
                log("done")
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Runs one engine here rather than in its own process, for the cpuset it buys.
     *
     * Deliberately not the normal path: everything the process split is for — a PSS reading that is
     * the model and not the UI, a native crash that takes down one engine — is given up for it.
     */
    private fun runInProcess(
        process: BackendProcess,
        modelPath: String?,
        options: Map<String, String>,
        outPath: String,
    ) {
        scope.launch {
            try {
                log("in-process run: cpuset ${cpuset()}, allowed ${CpuAffinity.current()}")
                options["affinity"]?.let { requested ->
                    val mask = if (requested == "big") CpuAffinity.bigCoreMask() else requested
                    mask?.let { log("affinity: ${CpuAffinity.apply(it).detail}") }
                }

                val model = modelPath ?: error("--es model is required for an in-process run")
                val corpus = PromptCorpus.parse(assets.open("prompts.json").bufferedReader().readText())
                val engineId = availableEngines().first { engine ->
                    engine.id.equals(process.label, ignoreCase = true) ||
                        process.label.contains(engine.id, ignoreCase = true) ||
                        engine.id.contains(process.label.replace("-", ""), ignoreCase = true)
                }.id

                val config = BenchmarkArguments.toConfig(
                    arguments = options + mapOf("engine" to engineId, "model" to model,
                        "cacheDir" to cacheDir.absolutePath),
                    corpusPromptIds = corpus.prompts.map { it.id },
                    runIdFallback = "in-process-${System.currentTimeMillis()}",
                )

                val file = BenchmarkRunner(config, corpus, log = { line -> log(line) }).run()
                ResultsTable.rows(listOf(file.toJson())).forEach { row ->
                    log("RESULT ${row.engineId} ${row.workload} tok/s=${row.tokensPerSecond.format()} " +
                        "ttft=${row.ttftMs.format()}ms tokens=${row.tokens} chunks=${row.chunks} " +
                        "peakPss=${row.peakPssMb.format()}MB [in-process, memory not comparable]")
                }
                writeResults(outPath, listOf(file.toJson()))
            } catch (failure: Throwable) {
                log("in-process run failed: ${failure::class.java.simpleName}: ${failure.message}")
            } finally {
                log("done")
                stopSelf()
            }
        }
    }

    private fun cpuset(): String =
        runCatching { File("/proc/self/cpuset").readText().trim() }.getOrDefault("unknown")

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Merged as a JSON array of the engines' own results files.
     *
     * Not re-serialised into a shape of this app's own: the harness owns that schema, and a second
     * writer of it is a second thing to keep in step.
     */
    private fun writeResults(path: String, results: List<String>) {
        if (results.isEmpty()) return
        runCatching {
            File(path).apply { parentFile?.mkdirs() }
                .writeText(results.joinToString(prefix = "[", separator = ",", postfix = "]"))
        }.onSuccess { log("results written to $path") }
            .onFailure { log("could not write $path: ${it.message}") }
    }

    private fun processesFrom(raw: String?): List<BackendProcess> {
        val requested = raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (requested.isEmpty() || requested.singleOrNull().equals("all", ignoreCase = true)) {
            return BackendProcess.entries.toList()
        }
        return requested.mapNotNull { name ->
            BackendProcess.entries.firstOrNull {
                it.label.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true)
            }
        }
    }

    /** Passed to the harness untouched, so its own argument parsing stays the only one. */
    private fun optionsFrom(intent: Intent): Map<String, String> = buildMap {
        HARNESS_OPTIONS.forEach { key ->
            intent.getStringExtra(key)?.let { put(key, it) }
            // --ei and --ez arrive typed; -1 and false are indistinguishable from absent, so only
            // a value the caller could not have meant as a default is taken.
            intent.takeIf { it.hasExtra(key) }?.let { extras ->
                val asInt = extras.getIntExtra(key, Int.MIN_VALUE)
                if (asInt != Int.MIN_VALUE) put(key, asInt.toString())
            }
        }
        if (intent.getBooleanExtra("gpu", false)) put("gpu", "true")
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Benchmark run", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("koinference")
                .setContentText("Running the benchmark")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .build(),
        )
    }

    private fun Double?.format(): String = this?.let { "%.1f".format(it) } ?: "-"

    private fun log(message: String) = Log.i(TAG, message)

    companion object {
        const val TAG = "koinference-benchmark"

        const val EXTRA_ENGINES = "engines"
        const val EXTRA_MODEL = "model"
        const val EXTRA_OUT = "out"

        /** Runs the engine in this process, for its cpuset. See [runInProcess]. */
        const val EXTRA_IN_PROCESS = "inProcess"

        /** Keys handed straight to the harness's own argument parsing. */
        private val HARNESS_OPTIONS = listOf(
            // Ours, not the harness's: it ignores keys it does not know, and the engine service
            // reads this one before it loads anything.
            "affinity",
            "promptSet", "iterations", "warmup", "maxNewTokens", "maxContextTokens",
            "threads", "temperature", "topK", "topP", "seed", "modelId", "quantization", "runId",
        )

        private const val DEFAULT_OUT = "benchmark-results.json"
        private const val CHANNEL_ID = "koinference-benchmark"
        private const val NOTIFICATION_ID = 3
    }
}
