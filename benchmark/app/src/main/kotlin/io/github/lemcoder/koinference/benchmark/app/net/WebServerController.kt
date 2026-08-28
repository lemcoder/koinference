package io.github.lemcoder.koinference.benchmark.app.net

import android.content.Context
import android.content.Intent
import io.github.lemcoder.koinference.benchmark.app.client.BackendConnection
import io.github.lemcoder.koinference.benchmark.app.client.BackendProcess
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Starts and stops [WebServerService] on behalf of the UI, and waits until it actually answers.
 *
 * Waiting matters: loading a 1.2B model takes seconds, and a screen that says "serving" before
 * `/healthz` replies is telling the user something it does not know.
 */
class WebServerController(
    private val context: Context,
    @Suppress("unused") private val connectionFor: (BackendProcess) -> BackendConnection,
) {

    /** Returns the URL a client on this network should dial. */
    suspend fun start(process: BackendProcess, modelPath: String): String {
        context.startForegroundService(
            Intent(context, WebServerService::class.java).apply {
                putExtra(WebServerService.EXTRA_BACKEND, process.label)
                putExtra(WebServerService.EXTRA_MODEL_PATH, modelPath)
            },
        )

        val host = WebServerService.localAddress() ?: "127.0.0.1"
        val url = "http://$host:${WebServerService.DEFAULT_PORT}"
        awaitHealthy(url)
        return url
    }

    fun stop() {
        context.stopService(Intent(context, WebServerService::class.java))
    }

    private suspend fun awaitHealthy(url: String) = withContext(Dispatchers.IO) {
        repeat(HEALTH_ATTEMPTS) {
            val healthy = runCatching {
                (URL("$url/healthz").openConnection() as HttpURLConnection).run {
                    connectTimeout = 1_000
                    readTimeout = 1_000
                    responseCode == 200
                }
            }.getOrDefault(false)
            if (healthy) return@withContext
            delay(HEALTH_INTERVAL_MS)
        }
        error("the server did not answer /healthz within ${HEALTH_ATTEMPTS * HEALTH_INTERVAL_MS / 1000}s")
    }

    private companion object {
        // Generous: a 1.2B model on a mid-range phone takes its time, and failing early would look
        // like a broken server rather than a slow load.
        const val HEALTH_ATTEMPTS = 60
        const val HEALTH_INTERVAL_MS = 1_000L
    }
}
