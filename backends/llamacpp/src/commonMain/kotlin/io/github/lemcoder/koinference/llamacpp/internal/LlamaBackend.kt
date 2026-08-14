package io.github.lemcoder.koinference.llamacpp.internal

/**
 * Process-wide llama.cpp backend initialisation.
 *
 * `koi_backend_init` has to run before the first model load, and `llama_backend_free` tears
 * down state every live model and session still points at. There is no ownership information
 * to reference-count here — a second loader may hold a model this one knows nothing about —
 * so the backend is initialised once and never freed. What it leaks is a fixed set of globals,
 * released by the process exiting; freeing it at the wrong moment is a crash.
 */
internal object LlamaBackend {

    // `lazy` rather than a flag: it is synchronised by default on both JVM and Kotlin/Native,
    // and two coroutines loading models on different threads must not both call init.
    private val initialized: Unit by lazy { llamaBackendInit() }

    fun ensureInitialized() = initialized
}
