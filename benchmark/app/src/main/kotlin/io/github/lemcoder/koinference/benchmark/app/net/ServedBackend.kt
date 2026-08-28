package io.github.lemcoder.koinference.benchmark.app.net

import kotlinx.coroutines.flow.Flow

/**
 * What the HTTP server needs from whatever is behind it.
 *
 * An interface because the server no longer holds a model: it runs in the app process and the model
 * is in the engine's, so everything here crosses a binder. Keeping that behind a type also means
 * the server can be exercised without a service.
 */
interface ServedBackend {

    val engineId: String

    val modelId: String

    val modelPath: String

    /** Reply text as it arrives. Buffering here would make time to first token equal total latency. */
    fun stream(prompt: String, schema: String?): Flow<String>

    /** The engine process's memory, as JSON. Read there, not here — see IBackendService. */
    suspend fun processMemory(): String
}
