package io.github.lemcoder.koinference.llamacpp.internal

internal expect fun llamaBackendInit()
internal expect fun llamaBackendFree()
internal expect fun llamaSystemInfo(): String

/** Returns an opaque model handle (C pointer stored as Long), or 0 on failure. */
internal expect fun llamaModelLoad(path: String): Long
internal expect fun llamaModelFree(handle: Long)

/**
 * Create a session over an already-loaded model.
 * Returns an opaque session handle, or 0 on failure.
 */
internal expect fun llamaSessionCreate(
    modelHandle: Long,
    nCtx: Int,
    nThreads: Int,
    nPredict: Int,
    temp: Float,
    topK: Int,
    minP: Float,
): Long

internal expect fun llamaSessionFree(handle: Long)

/**
 * Generate a response (blocking).
 * @param grammar  BNF grammar string for constrained generation, or null for unconstrained.
 */
internal expect fun llamaGenerate(
    sessionHandle: Long,
    systemPrompt: String?,
    userPrompt: String,
    grammar: String?,
): String

/** Compute text embeddings. Returns an empty array on failure. */
internal expect fun llamaEmbed(sessionHandle: Long, text: String): FloatArray
