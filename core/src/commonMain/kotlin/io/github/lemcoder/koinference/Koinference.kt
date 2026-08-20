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

    /**
     * Registered backends this device cannot run, by id, with the reason.
     *
     * Empty on a device that can run all of them. Read it at startup to hide a feature or warn once,
     * rather than discovering the same thing from a [load] that throws — an application that
     * registers two engines and only ever loads one container should not be stopped by the other
     * being unrunnable, which is why the constructor accepts them and [load] is where it becomes
     * an error.
     */
    val unsupported: Map<String, String>
        get() = backends.mapNotNull { backend ->
            backend.unsupportedReason()?.let { backend.id to it }
        }.toMap()

    // One loader per backend, kept so that unload and unloadAll reach the same loader a load used —
    // a loader owns the runtimes it handed out, and a second one would not know about them.
    private val loaders = mutableMapOf<String, ModelLoader>()

    /** The backend that reads this container, or null if none was registered for it. */
    fun backendFor(modelPath: String): Backend? = backends.firstOrNull { it.handles(modelPath) }

    fun backendById(id: String): Backend? = backends.firstOrNull { it.id == id }

    /**
     * Load [modelPath] with whichever registered backend reads it.
     *
     * One method, because there is nothing to choose between: every generating runtime speaks
     * [io.github.lemcoder.koinference.runtime.ResponsePart], and what a given model puts in a reply
     * is its own business rather than a different type. Read [Backend.modalities] if you want to know
     * before collecting.
     *
     * Loading the same path twice returns the same runtime — the weights are read once.
     *
     * @throws IllegalStateException if no registered backend reads this container, or if a backend's
     *         loader returns something that does not generate. The first message names what is
     *         registered, because the usual cause is a missing module rather than a bad path.
     * @throws BackendUnsupportedException if the backend that reads this container cannot run on
     *         this device. Thrown before the weights are opened, and before anything native is
     *         called — the failure it stands in for is a SIGILL that no `catch` would see.
     */
    suspend fun load(modelPath: String): GeneratingRuntime {
        val backend = backendFor(modelPath)
            ?: error("No registered backend reads $modelPath. Registered: $backendIds")
        backend.unsupportedReason()?.let { throw BackendUnsupportedException(backend.id, it) }
        val runtime = loaderFor(modelPath).load(modelPath)
        return runtime as? GeneratingRuntime
            ?: error("${backend.id} returned ${runtime::class.simpleName}, which does not generate")
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
