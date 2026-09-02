package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.backend.BackendUnsupportedException
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.runtime.ModelRuntime
import io.github.lemcoder.koinference.runtime.GeneratingRuntime

/**
 * The entry point: the backends an application was built with, and the models loaded through them.
 *
 * ```kotlin
 * val koi = Koinference(LlamaCpp, LiteRtLm)
 * val runtime = koi.load("/models/model.gguf")
 * val reply = runtime.generateResponse("What is the capital of France?")
 *     .filterIsInstance<ResponsePart.Text>()
 *     .joinToString("") { it.text }
 * ```
 *
 * Which engine reads a container is the backend's own answer, so a caller names a path rather than
 * an engine, and switching engines is changing the model file.
 *
 * **A class, not an object with `init`.** The shape asked for was
 * `Koinference.init(backends)` / `Koinference.load(path)`, and this gives the same two-line
 * ergonomics without the global: `load` before `init` would be a runtime failure rather than a
 * compile error, two consumers in one process would fight over one registry, and tests would need
 * to reset it between cases — this repository has spent a lot of effort removing exactly that kind
 * of hidden state. An application that wants one instance everywhere can hold this in its own
 * object; a library that embeds koinference can hold its own without disturbing anyone.
 *
 * @param config applied to every model this instance loads. Construct a second [Koinference] for a
 *        different configuration; a loader is bound to its config at creation on both engines.
 */
class Koinference(
    private val backends: List<Backend>,
    private val config: ModelConfig = ModelConfig(),
) {

    constructor(vararg backends: Backend, config: ModelConfig = ModelConfig()) :
        this(backends.toList(), config)

    init {
        val duplicates = backends.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate backend ids: $duplicates" }
        require(backends.isNotEmpty()) { "Koinference needs at least one backend" }
    }

    /** Ids of the registered backends, in the order they were given. */
    val backendIds: List<String> get() = backends.map { it.id }

    // One loader per backend, kept so that unload and unloadAll reach the same loader a load used —
    // a loader owns the runtimes it handed out, and a second one would not know about them.
    private val loaders = mutableMapOf<String, ModelLoader>()

    /** The backend that reads this container, or null if none was registered for it. */
    fun backendFor(modelPath: String): Backend? = backends.firstOrNull { it.handles(modelPath) }

    fun backendById(id: String): Backend? = backends.firstOrNull { it.id == id }

    /**
     * Load [modelPath] with whichever registered backend reads it.
     *
     * Returns the base [ModelRuntime], and the caller narrows it:
     *
     * ```kotlin
     * val runtime = koi.load(path)
     * (runtime as? GeneratingRuntime)?.generateResponse("hello")
     * (runtime as? EmbeddingRuntime)?.embed(listOf("hello"))
     * ```
     *
     * Not two load methods. This repository had `loadText` and `loadVision` once and removed them:
     * a method per kind multiplies with every kind, and the pair was already wrong when one reply
     * could hold text *and* audio. A loader genuinely cannot promise what it produced — the same
     * `.gguf` path could be a chat model or an embedding model — so the narrowing belongs where the
     * caller knows what it asked for. [Backend.modalities] says which kinds a backend produces
     * without loading anything.
     *
     * Loading the same path twice returns the same runtime — the weights are read once.
     *
     * @throws IllegalStateException if no registered backend reads this container. The message names
     *         what is registered, because the usual cause is a missing module rather than a bad path.
     * @throws BackendUnsupportedException if the engine cannot run on this device. A backend that
     *         can be installed on hardware it cannot use throws this from its own loader, before
     *         anything native is called.
     */
    suspend fun load(modelPath: String): ModelRuntime {
        backendFor(modelPath)
            ?: error("No registered backend reads $modelPath. Registered: $backendIds")
        return loaderFor(modelPath).load(modelPath)
    }

    /** Release the model at [modelPath]. Idempotent. */
    suspend fun unload(modelPath: String) {
        loaders[backendFor(modelPath)?.id]?.unload(modelPath)
    }

    /** Release every model this instance loaded. Idempotent; the instance stays usable. */
    suspend fun unloadAll() {
        loaders.values.forEach { it.unloadAll() }
    }

    private fun loaderFor(modelPath: String): ModelLoader {
        val backend = backendFor(modelPath)
            ?: error("No registered backend reads $modelPath. Registered: $backendIds")
        return loaders.getOrPut(backend.id) { backend.loader(config) }
    }
}
