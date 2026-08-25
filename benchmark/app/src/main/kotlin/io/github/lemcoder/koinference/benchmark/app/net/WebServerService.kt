package io.github.lemcoder.koinference.benchmark.app.net

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
import io.github.lemcoder.koinference.benchmark.platform.BenchmarkContext
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hosts the OpenAI-compatible server in the *app* process, in front of an engine in another one.
 *
 * The server is here rather than beside the model on purpose: `/koinference/memory` asks the engine
 * process for its own numbers, so the model's memory stays uncontaminated by Ktor, Compose and
 * whatever else this process holds.
 *
 * Startable from the UI and from a shell, because a scripted run should not need someone to tap
 * anything:
 *
 *     adb shell am start-foreground-service \
 *       -n io.github.lemcoder.koinference.benchmark.app/.net.WebServerService \
 *       --es backend llama.cpp --es modelPath /sdcard/Download/koinference/model.gguf
 */
class WebServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: InferenceServer? = null
    private var connection: BackendConnection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        BenchmarkContext.applicationContext = applicationContext
        startForegroundNotification()

        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
        val backendLabel = intent.getStringExtra(EXTRA_BACKEND)
        if (modelPath == null || backendLabel == null) {
            log("need both $EXTRA_BACKEND and $EXTRA_MODEL_PATH; nothing to serve")
            stopSelf()
            return START_NOT_STICKY
        }

        val process = BackendProcess.entries.firstOrNull {
            it.label.equals(backendLabel, ignoreCase = true) || it.name.equals(backendLabel, ignoreCase = true)
        }
        if (process == null) {
            log("unknown backend '$backendLabel'; known: ${BackendProcess.entries.map { it.label }}")
            stopSelf()
            return START_NOT_STICKY
        }

        val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
        val bind = intent.getStringExtra(EXTRA_BIND) ?: DEFAULT_BIND
        val maxNewTokens = intent.getIntExtra(EXTRA_MAX_NEW_TOKENS, DEFAULT_MAX_NEW_TOKENS)

        scope.launch {
            try {
                stopServer()
                val backendConnection = BackendConnection(applicationContext, process.serviceClass)
                connection = backendConnection

                log("loading $modelPath on ${process.label}")
                backendConnection.load(
                    modelPath = modelPath,
                    options = mapOf(
                        "maxNewTokens" to maxNewTokens.toString(),
                        "threads" to intent.getIntExtra(EXTRA_THREADS, 0).toString(),
                        "contextTokens" to intent.getIntExtra(EXTRA_CONTEXT_TOKENS, 0).toString(),
                        "gpu" to intent.getBooleanExtra(EXTRA_GPU, false).toString(),
                        "temperature" to intent.getDoubleExtra(EXTRA_TEMPERATURE, 0.0).toString(),
                        "seed" to intent.getIntExtra(EXTRA_SEED, DEFAULT_SEED).toString(),
                    ),
                )

                InferenceServer(
                    model = BinderServedBackend(backendConnection, backendConnection.backendId(), modelPath),
                    port = port,
                    bindAddress = bind,
                    maxNewTokens = maxNewTokens,
                    onLog = ::log,
                ).also { server = it }.start()
            } catch (failure: Throwable) {
                // Stopped rather than left up with no model: a server that fails every request
                // wastes whatever run was pointed at it.
                log("could not serve: ${failure::class.java.simpleName}: ${failure.message}")
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.launch { stopServer() }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun stopServer() {
        server?.stop()
        server = null
        connection?.let {
            runCatching { it.unload() }
            it.disconnect()
        }
        connection = null
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Inference server", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("koinference")
                .setContentText("Serving on port $DEFAULT_PORT")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .build(),
        )
    }

    private fun log(message: String) = Log.i(TAG, message)

    companion object {
        const val TAG = "koinference-server"

        const val EXTRA_BACKEND = "backend"
        const val EXTRA_MODEL_PATH = "modelPath"
        const val EXTRA_PORT = "port"
        const val EXTRA_BIND = "bind"
        const val EXTRA_MAX_NEW_TOKENS = "maxNewTokens"
        const val EXTRA_THREADS = "threads"
        const val EXTRA_CONTEXT_TOKENS = "contextTokens"
        const val EXTRA_GPU = "gpu"
        const val EXTRA_TEMPERATURE = "temperature"
        const val EXTRA_SEED = "seed"

        const val DEFAULT_PORT = 8080

        /**
         * Every interface, no authentication. Deliberate for a benchmark device on a lab network —
         * pass `--es bind 127.0.0.1` and use `adb forward` anywhere else.
         */
        const val DEFAULT_BIND = "0.0.0.0"
        const val DEFAULT_MAX_NEW_TOKENS = 256
        const val DEFAULT_SEED = 42

        private const val CHANNEL_ID = "koinference-server"
        private const val NOTIFICATION_ID = 2

        /** First non-loopback IPv4 address: what a client on this network dials. */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()
                ?.hostAddress
        }.getOrNull()
    }
}
