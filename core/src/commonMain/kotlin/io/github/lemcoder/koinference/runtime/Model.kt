package io.github.lemcoder.koinference.runtime

/**
 * A model loaded into memory: the weights, and the ability to open connections over them.
 *
 * This is what a backend's loader now returns — the coarse resource. It carries no decode state; a
 * [Connection] does. Splitting them is what lets several connections share one set of weights, and
 * what removes the in-place "reload the model to retune it" dance the old `ModelRuntime` needed: a
 * connection is opened cheaply and torn down independently, and the weights are released once, here.
 *
 * Where the model runs (CPU/GPU) is fixed when it is loaded — llama.cpp decides offload at load
 * time — so there is no "move it" call. To run the same weights on a different accelerator, load a
 * second model; that is a new set of weights, honestly, rather than a mutation that can half-fail.
 */
interface Model {

    /**
     * Open a connection — a decode context — over these weights.
     *
     * Returns the base [Connection]; the caller narrows it to what the model produces
     * ([GeneratingConnection], [EmbeddingConnection]). A backend that generates returns a generating
     * connection, an embedding backend an embedding one; [io.github.lemcoder.koinference.backend.Backend.modalities]
     * says which without opening anything.
     *
     * @throws KoinferenceException.Unsupported if a connection of the requested kind cannot be made.
     */
    suspend fun open(): Connection

    /** Release the weights. Idempotent. Open connections must be closed first; see [Koinference.unloadModel]. */
    suspend fun close()
}
