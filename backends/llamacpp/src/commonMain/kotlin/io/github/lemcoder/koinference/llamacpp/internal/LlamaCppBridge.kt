package io.github.lemcoder.koinference.llamacpp.internal

internal expect fun llamaBackendInit()
internal expect fun llamaBackendFree()
internal expect fun llamaSystemInfo(): String

/**
 * Returns an opaque model handle (C pointer stored as Long), or 0 on failure.
 *
 * @param nGpuLayers layers to offload; 0 = CPU only. Offload is decided at load time in
 *        llama.cpp, so it is here rather than on the session.
 */
internal expect fun llamaModelLoad(path: String, nGpuLayers: Int): Long
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

/**
 * Convert a JSON schema to the GBNF grammar [llamaGenerate] takes.
 *
 * @return the grammar, or an empty string if the schema does not parse or convert.
 */
internal expect fun llamaJsonSchemaToGrammar(schema: String): String

/**
 * Start a streaming generation. Returns the prompt's token count, or -1 on failure.
 *
 * A pull loop rather than a callback: the JVM leg goes through generated JNI bridges, which
 * cannot hand a C callback back into the JVM. Timing lives in the caller, not here — that is
 * what lets one clock measure every engine identically.
 */
internal expect fun llamaGenerateBegin(
    sessionHandle: Long,
    systemPrompt: String?,
    userPrompt: String,
    grammar: String?,
): Int

/** Next chunk, or null when the generation is finished. Throws on error. */
internal expect fun llamaGenerateNext(sessionHandle: Long): String?

/** Releases the generation. Safe to call when none is running; required if a loop is abandoned. */
internal expect fun llamaGenerateEnd(sessionHandle: Long)

/** Tokens in [text] by the model's vocabulary, or -1 when the session is gone. */
internal expect fun llamaTokenCount(sessionHandle: Long, text: String): Int
