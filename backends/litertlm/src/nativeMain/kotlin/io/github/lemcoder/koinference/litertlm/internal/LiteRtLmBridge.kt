@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.litertlm.internal

import cnames.structs.KoiLmConversation
import cnames.structs.KoiLmEngine
// The cinterop package is named after the .def file, not the interop, so it is
// koinference_litertlm rather than koinferenceLiteRtLm.
import koinference_litertlm.KoiLmSessionParams
import koinference_litertlm.koilm_generate
import koinference_litertlm.koilm_last_error
import koinference_litertlm.koilm_model_free
import koinference_litertlm.koilm_model_load
import koinference_litertlm.koilm_session_create
import koinference_litertlm.koilm_session_free
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString

/** 1 MiB. Replies are JSON-wrapped, so this is the envelope size, not the token budget. */
private const val GEN_BUF_SIZE = 1 shl 20

internal actual class LiteRtLmEngine(val handle: Long)
internal actual class LiteRtLmConversation(val handle: Long)

private fun lastError(): String = koilm_last_error()?.toKString().orEmpty()

internal actual fun openEngine(
    path: String,
    cacheDir: String?,
    backend: Int,
    nThreads: Int,
    maxTokens: Int,
): LiteRtLmEngine {
    val handle = koilm_model_load(path, cacheDir, backend, nThreads, maxTokens)
        ?.rawValue?.toLong() ?: 0L
    check(handle != 0L) { "Could not load $path: ${lastError()}" }
    return LiteRtLmEngine(handle)
}

internal actual fun closeEngine(engine: LiteRtLmEngine) {
    koilm_model_free(engine.handle.toCPointer<KoiLmEngine>())
}

internal actual fun openConversation(
    engine: LiteRtLmEngine,
    maxTokens: Int,
    topK: Int,
    topP: Float,
    temp: Float,
    systemPrompt: String?,
): LiteRtLmConversation {
    // koilm_default_session_params() returns a CValue — an immutable off-heap snapshot — so
    // its fields cannot be assigned through it. Every field is set here anyway.
    val params = cValue<KoiLmSessionParams> {
        max_tokens = maxTokens
        top_k = topK
        this.top_p = topP // qualified: unqualified top_p is this function's parameter
        this.temp = temp
    }
    val handle = koilm_session_create(
        engine.handle.toCPointer<KoiLmEngine>(),
        params,
        systemPrompt,
    )?.rawValue?.toLong() ?: 0L
    check(handle != 0L) { "Could not open a LiteRT-LM conversation: ${lastError()}" }
    return LiteRtLmConversation(handle)
}

internal actual fun closeConversation(conversation: LiteRtLmConversation) {
    koilm_session_free(conversation.handle.toCPointer<KoiLmConversation>())
}

internal actual fun generate(
    conversation: LiteRtLmConversation,
    prompt: String,
    jsonSchema: String?,
): String = memScoped {
    val buf = allocArray<ByteVar>(GEN_BUF_SIZE)
    val written = koilm_generate(
        conversation.handle.toCPointer<KoiLmConversation>(),
        prompt,
        jsonSchema,
        buf,
        GEN_BUF_SIZE,
    )
    check(written >= 0) { "LiteRT-LM generation failed: ${lastError()}" }
    extractResponseText(buf.toKString())
}
