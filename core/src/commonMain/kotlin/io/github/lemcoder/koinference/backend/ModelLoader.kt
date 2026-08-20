package io.github.lemcoder.koinference.backend

import io.github.lemcoder.koinference.runtime.ModelRuntime

/**
 * Owns loaded models and hands out runtimes over them.
 *
 * A loader is a resource: every runtime it returns holds native memory that outlives the
 * Kotlin object graph, so dropping the last reference to a loader without unloading leaks
 * whatever it still holds. There is no finalizer to fall back on — [unloadAll] is the way out
 * when the caller no longer tracks individual paths.
 */
interface ModelLoader {
    /**
     * Load [modelPath], or return the runtime already loaded for it.
     *
     * Returns the base [ModelRuntime], because with more than one modality a loader cannot promise
     * which kind it produced. Callers should go through `Koinference.loadText` or `loadVision`,
     * which narrow it against the backend's declared [io.github.lemcoder.koinference.runtime.Modality]
     * and fail with a message naming the mismatch rather than a ClassCastException.
     *
     * Safe to call concurrently for the same path: the weights are loaded once and every
     * caller gets the same runtime.
     */
    suspend fun load(modelPath: String): ModelRuntime

    /** Release the runtime for [modelPath], if any. Idempotent. */
    suspend fun unload(modelPath: String)

    /** Release every runtime this loader holds. Idempotent; the loader stays usable. */
    suspend fun unloadAll()
}
