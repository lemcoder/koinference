package io.github.lemcoder.koinference.benchmark.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.github.lemcoder.koinference.GenerationParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Holds the model and serves it, alone in its own process.
 *
 * `android:process=":inference"` in the manifest is the whole reason this is a service rather
 * than something the Activity starts in-process. A model's memory is most of what anyone wants
 * to measure, and in a shared process it arrives mixed with the UI toolkit, the HTTP client the
 * UI uses, and whatever the test runner brought with it. Alone, `Debug.getMemoryInfo()` inside
 * this process is the model, the engine, and a small server — and `adb shell dumpsys meminfo`
 * shows it as its own line.
 *
 * Foreground, because Android stops background services and a benchmark that dies at minute
 * eleven of a twenty-minute sustained run has measured nothing.
 */
class InferenceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: InferenceServer? = null
    private var model: LoadedModel? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        startForegroundNotification()

        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
        if (modelPath == null) {
            log("no ${EXTRA_MODEL_PATH} extra; nothing to serve")
            stopSelf()
            return START_NOT_STICKY
        }

        // The harness's Android probe reads device facts through this; without it the
        // /koinference/device endpoint would report a device with every field null.
        io.github.lemcoder.koinference.benchmark.BenchmarkContext.applicationContext = applicationContext

        val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
        val bind = intent.getStringExtra(EXTRA_BIND) ?: DEFAULT_BIND
        val maxNewTokens = intent.getIntExtra(EXTRA_MAX_NEW_TOKENS, DEFAULT_MAX_NEW_TOKENS)

        scope.launch {
            try {
                log("loading $modelPath")
                val loaded = LoadedModel.load(
                    modelPath = modelPath,
                    maxNewTokens = maxNewTokens,
                    parameters = GenerationParameters(
                        temperature = intent.getDoubleExtra(EXTRA_TEMPERATURE, 0.0),
                        seed = intent.getIntExtra(EXTRA_SEED, DEFAULT_SEED),
                    ),
                    threads = intent.getIntExtra(EXTRA_THREADS, 0),
                    contextTokens = intent.getIntExtra(EXTRA_CONTEXT_TOKENS, 0),
                    useGpu = intent.getBooleanExtra(EXTRA_GPU, false),
                )
                model = loaded
                log("loaded ${loaded.modelId} on ${loaded.engineId} in ${loaded.modelLoadMs} ms")

                InferenceServer(
                    model = loaded,
                    port = port,
                    bindAddress = bind,
                    maxNewTokens = maxNewTokens,
                    onLog = ::log,
                ).also { server = it }.start()
            } catch (failure: Throwable) {
                // Logged and then stopped, rather than left running with no model: a server that
                // answers requests by failing every one of them wastes a benchmark run.
                log("failed to start: ${failure::class.java.simpleName}: ${failure.message}")
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        scope.launch { model?.unload() }
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Inference server", NotificationManager.IMPORTANCE_LOW),
            )
        }

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("koinference")
            .setContentText("Serving a model on port $DEFAULT_PORT")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    companion object {
        const val TAG = "koinference-server"

        const val EXTRA_MODEL_PATH = "modelPath"
        const val EXTRA_PORT = "port"
        const val EXTRA_BIND = "bind"
        const val EXTRA_MAX_NEW_TOKENS = "maxNewTokens"
        const val EXTRA_TEMPERATURE = "temperature"
        const val EXTRA_SEED = "seed"
        const val EXTRA_THREADS = "threads"
        const val EXTRA_CONTEXT_TOKENS = "contextTokens"
        const val EXTRA_GPU = "gpu"

        const val DEFAULT_PORT = 8080

        /**
         * Every interface, no authentication — see [InferenceServer]. Set `bind` to 127.0.0.1
         * and use `adb forward` on a network you do not control.
         */
        const val DEFAULT_BIND = "0.0.0.0"
        const val DEFAULT_MAX_NEW_TOKENS = 256
        const val DEFAULT_SEED = 42

        private const val CHANNEL_ID = "koinference-inference"
        private const val NOTIFICATION_ID = 1
    }
}
