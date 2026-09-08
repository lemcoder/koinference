package io.github.lemcoder.koinference.backend

import io.github.lemcoder.koinference.runtime.Model

/**
 * Owns loaded weights and hands out [Model]s over them.
 *
 * A loader is a resource: every model it returns holds native memory that outlives the Kotlin
 * object graph, so dropping the last reference to a loader without unloading leaks whatever it still
 * holds. There is no finalizer — [unloadAll] is the way out when the caller no longer tracks paths.
 */
interface ModelLoader {

    /**
     * Load [modelPath], or return the [Model] already loaded for it.
     *
     * Returns the weights, not a usage of them — a [io.github.lemcoder.koinference.runtime.Connection]
     * is opened over the model. A loader cannot promise what the weights can do, so the caller narrows
     * the connection the model opens, not the model.
     *
     * Safe to call concurrently for the same path: the weights are loaded once and every caller gets
     * the same [Model].
     */
    suspend fun load(modelPath: String): Model

    /** Release the model for [modelPath], if any. Idempotent. */
    suspend fun unload(modelPath: String)

    /** Release every model this loader holds. Idempotent; the loader stays usable. */
    suspend fun unloadAll()
}
