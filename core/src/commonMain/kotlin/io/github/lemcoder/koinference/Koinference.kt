package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.runtime.Connection
import io.github.lemcoder.koinference.runtime.EmbeddingConnection
import io.github.lemcoder.koinference.runtime.GeneratingConnection
import io.github.lemcoder.koinference.runtime.KoinferenceException
import io.github.lemcoder.koinference.runtime.Model

/**
 * The entry point: the backends an application was built with, the models it loads, and the
 * connections opened over them.
 *
 * ```kotlin
 * val koi = Koinference(LlamaCpp, LiteRtLm)
 * val id  = koi.loadModel("/models/model.gguf")            // weights into RAM
 * val conn = koi.openConnection(id) as GeneratingConnection // a usage over them
 * val text = conn.generateAll("What is the capital of France?")
 *     .filterIsInstance<ResponsePart.Text>().joinToString("") { it.text }
 * conn.close()
 * ```
 *
 * Which engine reads a container is the backend's own answer, so a caller names a path, not an
 * engine. Loading splits from opening: [loadModel] puts weights in memory and returns a [ModelId];
 * [openConnection] opens a [Connection] over that id, possibly several times. The caller narrows the
 * connection to [GeneratingConnection] or [EmbeddingConnection] — a loader cannot promise which,
 * since the same `.gguf` could be a chat model or an embedding model; [Backend.modalities] says
 * which kinds a backend produces without loading anything.
 *
 * **A class, not an object.** A global would make `load` before construction a runtime rather than
 * a compile error, let two consumers in one process fight over one registry, and force tests to
 * reset it between cases. An application that wants one instance everywhere holds this in its own
 * object.
 *
 * @param config applied to every model this instance loads. Construct a second [Koinference] for a
 *        different configuration.
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

    /** The backend that reads this container, or null if none was registered for it. */
    fun backendFor(modelPath: String): Backend? = backends.firstOrNull { it.handles(modelPath) }

    fun backendById(id: String): Backend? = backends.firstOrNull { it.id == id }

    // One loader per backend, kept so unload reaches the loader a load used — a loader owns the
    // models it handed out, and a second one would not know about them.
    private val loaders = mutableMapOf<String, ModelLoader>()

    private class Tracked(val connection: Connection, val onDeath: (KoinferenceException) -> Unit)

    private class Loaded(
        val backendId: String,
        val modelPath: String,
        val model: Model,
        val connections: MutableList<Tracked> = mutableListOf(),
    )

    private val byId = mutableMapOf<ModelId, Loaded>()
    private val byPath = mutableMapOf<String, ModelId>()
    private var counter = 0

    /**
     * Load [modelPath] into memory and return a handle to it. The weights, not a usage: nothing
     * decodes until a [Connection] is opened over the returned id. Loading the same path twice
     * returns the same id — the weights are read once.
     *
     * @throws KoinferenceException.LoadFailed if no registered backend reads it, or the load fails.
     */
    suspend fun loadModel(modelPath: String): ModelId {
        byPath[modelPath]?.let { return it }
        val backend = backendFor(modelPath)
            ?: throw KoinferenceException.LoadFailed(
                "No registered backend reads $modelPath. Registered: $backendIds")
        val model = try {
            loaderFor(backend).load(modelPath)
        } catch (failure: KoinferenceException) {
            throw failure
        } catch (failure: Throwable) {
            throw KoinferenceException.LoadFailed("Could not load $modelPath: ${failure.message}", failure)
        }
        val id = ModelId("${backend.id}-${counter++}")
        byId[id] = Loaded(backend.id, modelPath, model)
        byPath[modelPath] = id
        return id
    }

    /**
     * Open a connection over a loaded model. Narrow the result to what the model does.
     *
     * [onDeath] is the out-of-band channel: it fires only when the connection dies with no call in
     * flight — the model unloaded under it, or (once wired to the process seam) the engine killed.
     * Failures of a call you await are thrown from that call instead. A consumer who wants to observe
     * liveness as a stream wraps [onDeath] with `callbackFlow { }`.
     *
     * @throws KoinferenceException.UnknownModel if [id] is not loaded.
     */
    suspend fun openConnection(
        id: ModelId,
        onDeath: (KoinferenceException) -> Unit = {},
    ): Connection {
        val loaded = byId[id] ?: throw KoinferenceException.UnknownModel(id)
        val raw = loaded.model.open()
        val tracked = Tracked(raw, onDeath)
        loaded.connections += tracked
        val untrack: suspend () -> Unit = { loaded.connections.remove(tracked) }
        // Wrap only to untrack on close, preserving the narrowable subtype via delegation.
        return when (raw) {
            is GeneratingConnection -> object : GeneratingConnection by raw {
                override suspend fun close() { raw.close(); untrack() }
            }
            is EmbeddingConnection -> object : EmbeddingConnection by raw {
                override suspend fun close() { raw.close(); untrack() }
            }
            else -> object : Connection by raw {
                override suspend fun close() { raw.close(); untrack() }
            }
        }
    }

    /**
     * Unload the model behind [id]. Refuses while connections are open unless [force] is set, in
     * which case each open connection is closed and told its model went away (via `onDeath`) before
     * the weights are released. Idempotent.
     */
    suspend fun unloadModel(id: ModelId, force: Boolean = false) {
        val loaded = byId[id] ?: return
        if (loaded.connections.isNotEmpty() && !force) {
            throw KoinferenceException.LoadFailed(
                "$id still has ${loaded.connections.size} open connection(s); close them or unload with force")
        }
        loaded.connections.toList().forEach { tracked ->
            tracked.connection.close()
            tracked.onDeath(KoinferenceException.ModelUnloaded(id))
        }
        byId.remove(id)
        byPath.remove(loaded.modelPath)
        // The loader owns the weights and closes them; a second close here would be a double free.
        loaders[loaded.backendId]?.unload(loaded.modelPath)
    }

    /** Release every model this instance loaded, closing their connections. Idempotent. */
    suspend fun unloadAll() {
        byId.keys.toList().forEach { unloadModel(it, force = true) }
        loaders.values.forEach { it.unloadAll() }
    }

    private fun loaderFor(backend: Backend): ModelLoader =
        loaders.getOrPut(backend.id) { backend.loader(config) }
}
