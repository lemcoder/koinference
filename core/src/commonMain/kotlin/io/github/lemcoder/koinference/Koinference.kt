package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.runtime.Modality
import io.github.lemcoder.koinference.runtime.ModelRuntime
import io.github.lemcoder.koinference.runtime.text.TextModelRuntime
import io.github.lemcoder.koinference.runtime.vision.ImageModelRuntime

/**
 * The entry point: the backends an application was built with, and the models loaded through them.
 *
 * ```kotlin
 * val koi = Koinference(LlamaCpp, LiteRtLm)
 * val runtime = koi.loadText("/models/model.gguf")
 * val reply = runtime.generateResponse("What is the capital of France?")
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
     * Returns the base runtime — the settings and parameters every model has. Use [loadText] or
     * [loadVision] to get something that can generate; they narrow this against the backend's
     * declared [Modality].
     *
     * Loading the same path twice returns the same runtime — the weights are read once.
     *
     * @throws IllegalStateException if no registered backend reads this container. The message
     *         names what is registered, because the usual cause is a missing module rather than a
     *         bad path.
     */
    suspend fun load(modelPath: String): ModelRuntime = loaderFor(modelPath).load(modelPath)

    /**
     * Load [modelPath] as a model that answers in text.
     *
     * The modality is checked against the backend *before* the weights are read, so asking a
     * vision-only engine for text costs nothing and says so. The cast afterwards is the library's
     * rather than the caller's: a backend whose declared modality disagrees with what its loader
     * returns is a bug in that backend, and this is where it surfaces with a legible message.
     */
    suspend fun loadText(modelPath: String): TextModelRuntime =
        loadAs(modelPath, Modality.TEXT)

    /** Load [modelPath] as a model that answers with an image. See [loadText]. */
    suspend fun loadVision(modelPath: String): ImageModelRuntime =
        loadAs(modelPath, Modality.IMAGE)

    private suspend inline fun <reified R : ModelRuntime> loadAs(
        modelPath: String,
        modality: Modality,
    ): R {
        val backend = backendFor(modelPath)
            ?: error("No registered backend reads $modelPath. Registered: $backendIds")
        check(modality in backend.modalities) {
            "${backend.id} reads $modelPath but produces ${backend.modalities}, not $modality"
        }
        val runtime = loaderFor(modelPath).load(modelPath)
        return runtime as? R
            ?: error(
                "${backend.id} declares $modality but its loader returned " +
                    "${runtime::class.simpleName}",
            )
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
