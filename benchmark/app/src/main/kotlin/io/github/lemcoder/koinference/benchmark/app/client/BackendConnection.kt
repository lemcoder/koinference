package io.github.lemcoder.koinference.benchmark.app.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.github.lemcoder.koinference.benchmark.app.IBackendService
import io.github.lemcoder.koinference.benchmark.app.IBenchmarkCallback
import io.github.lemcoder.koinference.benchmark.app.IGenerationCallback
import io.github.lemcoder.koinference.benchmark.app.IStatusCallback
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One backend service, bound, with the oneway callbacks turned back into suspending calls.
 *
 * A binder that dies takes its process with it — a native crash in an engine is exactly that — so
 * every pending call is failed rather than left hanging when the connection drops.
 */
class BackendConnection(
    private val context: Context,
    private val serviceClass: Class<*>,
) {

    private var service: IBackendService? = null
    private var connection: ServiceConnection? = null

    /** Binds, and suspends until the service is there. Idempotent. */
    suspend fun connect(): IBackendService = service ?: suspendCancellableCoroutine { continuation ->
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val bound = IBackendService.Stub.asInterface(binder)
                service = bound
                if (continuation.isActive) continuation.resume(bound)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                // The engine process died; the next call binds again rather than using a stale proxy.
                service = null
            }
        }
        connection = serviceConnection

        val intent = Intent(context, serviceClass)
        // Started as well as bound: a bind alone dies with the last client, and a benchmark run
        // must survive the Activity going away.
        context.startForegroundService(intent)
        val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!bound) continuation.resumeWithException(IllegalStateException("cannot bind ${serviceClass.simpleName}"))

        continuation.invokeOnCancellation { disconnect() }
    }

    fun disconnect() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        service = null
    }

    suspend fun backendId(): String = connect().backendId()

    /** Null when this device can run the engine. See `docs/backends.md`. */
    suspend fun unsupportedReason(): String? = connect().unsupportedReason()

    suspend fun modelPaths(): List<String> = connect().modelPaths()

    /** The engine process's memory, as JSON. Read there, where a model is what the numbers mean. */
    suspend fun processMemory(): String = connect().processMemory()

    /**
     * Runs the suite in the service's process and returns the results file as JSON.
     *
     * [onProgress] is called on a binder thread, so whatever it touches has to expect that.
     */
    suspend fun runBenchmark(
        modelPath: String,
        options: Map<String, String>,
        onProgress: (String) -> Unit,
    ): String {
        val service = connect()
        return suspendCancellableCoroutine { continuation ->
        val callback = object : IBenchmarkCallback.Stub() {
            override fun onProgress(message: String) = onProgress(message)

            override fun onFinished(resultsJson: String) {
                if (continuation.isActive) continuation.resume(resultsJson)
            }

            override fun onFailed(message: String) {
                if (continuation.isActive) continuation.resumeWithException(BackendCallFailed(message))
            }
        }
        service.runBenchmark(modelPath, options.toJsonObject(), callback)
        }
    }

    suspend fun load(modelPath: String, options: Map<String, String>): String {
        val service = connect()
        return suspendCancellableCoroutine { continuation ->
            service.load(modelPath, options.toJsonObject(), statusCallback(continuation::resume) {
                continuation.resumeWithException(BackendCallFailed(it))
            })
        }
    }

    suspend fun unload() {
        val service = connect()
        return suspendCancellableCoroutine { continuation ->
            service.unload(statusCallback({ continuation.resume(Unit) }) {
                continuation.resumeWithException(BackendCallFailed(it))
            })
        }
    }

    /**
     * Reply text as it arrives.
     *
     * A flow rather than a list because the HTTP server streams SSE, and buffering here would make
     * time to first token equal total latency for every client of it.
     */
    fun generate(prompt: String, schema: String?): Flow<String> = callbackFlow {
        val request = buildString {
            append("{\"prompt\":").append(prompt.jsonString())
            if (schema != null) append(",\"schema\":").append(schema.jsonString())
            append("}")
        }

        val callback = object : IGenerationCallback.Stub() {
            override fun onChunk(text: String) {
                trySend(text)
            }

            override fun onFinished(statsJson: String) {
                close()
            }

            override fun onFailed(message: String) {
                close(BackendCallFailed(message))
            }
        }

        connect().generate(request, callback)
        awaitClose { }
    }

    private inline fun statusCallback(
        crossinline onReady: (String) -> Unit,
        crossinline onFailed: (String) -> Unit,
    ) = object : IStatusCallback.Stub() {
        override fun onReady(infoJson: String) = onReady(infoJson)
        override fun onFailed(message: String) = onFailed(message)
    }
}

/** The service reported a failure; the message is the engine's own words. */
class BackendCallFailed(message: String) : RuntimeException(message)

private fun Map<String, String>.toJsonObject(): String =
    entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "${key.jsonString()}:${value.jsonString()}"
    }

private fun String.jsonString(): String = buildString {
    append('"')
    this@jsonString.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
        }
    }
    append('"')
}
