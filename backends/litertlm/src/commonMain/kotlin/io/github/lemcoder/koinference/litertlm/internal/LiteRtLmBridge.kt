package io.github.lemcoder.koinference.litertlm.internal

/**
 * The seam between the common runtime and whichever LiteRT-LM binding a target has.
 *
 * It is deliberately not the C API's shape. Apple targets reach LiteRT-LM through the facade
 * in `native/` and hold raw pointers; Android cannot, because the runtime it ships
 * (`liblitertlm_jni.so` in the AAR) exports only its 24 `Java_..._LiteRtLmJni_*` entry points
 * and no `litert_lm_*` C symbols at all — its visibility is pinned by a version script. So the
 * Android leg goes through Google's own Kotlin API and holds objects. Handles are opaque
 * classes rather than Longs so that neither shape has to pretend to be the other.
 *
 * Every function here throws on failure; callers do not check for null or zero.
 */

/** An engine with a model loaded into it. */
internal expect class LiteRtLmEngine

/** One conversation over an engine, carrying its own prefilled state. */
internal expect class LiteRtLmConversation

internal expect fun openEngine(
    path: String,
    cacheDir: String?,
    backend: Int,
    nThreads: Int,
    maxTokens: Int,
): LiteRtLmEngine

internal expect fun closeEngine(engine: LiteRtLmEngine)

internal expect fun openConversation(
    engine: LiteRtLmEngine,
    maxTokens: Int,
    topK: Int,
    topP: Float,
    temp: Float,
    systemPrompt: String?,
): LiteRtLmConversation

internal expect fun closeConversation(conversation: LiteRtLmConversation)

/**
 * Send one message and wait for the reply (blocking).
 *
 * @param jsonSchema JSON schema for constrained decoding, or null for unconstrained.
 * @return the assistant's text, already unwrapped from whatever envelope the platform's
 *         binding returns.
 */
internal expect fun generate(
    conversation: LiteRtLmConversation,
    prompt: String,
    jsonSchema: String?,
): String
