@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.litertlm.internal

import cnames.structs.KoiLmConversation
import cnames.structs.KoiLmEngine
import io.github.lemcoder.koinference.InferenceBackend
// The cinterop package is named after the .def file, not the interop, so it is
// koinference_litertlm rather than koinferenceLiteRtLm.
import koinference_litertlm.KOILM_BACKEND_CPU
import koinference_litertlm.KOILM_BACKEND_GPU
import koinference_litertlm.KoiLmSessionParams
import koinference_litertlm.koilm_generate
import koinference_litertlm.koilm_last_error
import koinference_litertlm.koilm_last_response
import koinference_litertlm.koilm_model_free
import koinference_litertlm.koilm_model_load
import koinference_litertlm.koilm_session_create
import koinference_litertlm.koilm_session_free
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString

/**
 * 64 KiB. Replies are JSON-wrapped, so this is the envelope size, not the token budget — and
 * it is only the first guess: a longer reply is collected from the facade rather than lost.
 */
private const val INITIAL_REPLY_BYTES = 1 shl 16

/** Leaves the runtime's own seeding, matching koilm_default_session_params(). */
private const val UNSEEDED = -1

internal actual fun platformBridge(): LiteRtLmBridge = FacadeBridge

private fun lastError(): String = koilm_last_error()?.toKString().orEmpty()

private object FacadeBridge : LiteRtLmBridge {
    override fun openEngine(options: EngineOptions): LiteRtLmEngine {
        val handle = koilm_model_load(
            options.modelPath,
            options.cacheDir,
            // toInt: cinterop gives an anonymous C enum's constants as UInt, and the
            // parameter they are for is a plain int.
            when (options.backend) {
                InferenceBackend.CPU -> KOILM_BACKEND_CPU
                InferenceBackend.GPU -> KOILM_BACKEND_GPU
            }.toInt(),
            options.nThreads,
            options.maxTokens,
        )
        checkNotNull(handle) { "Could not load ${options.modelPath}: ${lastError()}" }
        return FacadeEngine(handle)
    }
}

private class FacadeEngine(private val handle: CPointer<KoiLmEngine>) : LiteRtLmEngine {

    override fun openConversation(options: ConversationOptions): LiteRtLmConversation {
        // koilm_default_session_params() returns a CValue — an immutable off-heap snapshot —
        // so its fields cannot be assigned through it. Every field is set here anyway.
        val params = cValue<KoiLmSessionParams> {
            max_tokens = options.maxTokens
            top_k = options.topK
            this.top_p = options.topP // qualified: unqualified top_p is the struct field's setter argument
            this.temp = options.temperature
            seed = options.seed ?: UNSEEDED
        }
        val conversation = koilm_session_create(handle, params, options.systemPrompt)
        checkNotNull(conversation) { "Could not open a LiteRT-LM conversation: ${lastError()}" }
        return FacadeConversation(conversation)
    }

    override fun close() {
        koilm_model_free(handle)
    }
}

private class FacadeConversation(
    private val handle: CPointer<KoiLmConversation>,
) : LiteRtLmConversation {

    override fun generate(prompt: String, jsonSchema: String?): GeneratedReply = memScoped {
        val buffer = allocArray<ByteVar>(INITIAL_REPLY_BYTES)
        val needed = koilm_generate(handle, prompt, jsonSchema, buffer, INITIAL_REPLY_BYTES)
        check(needed >= 0) { "LiteRT-LM generation failed: ${lastError()}" }

        // needed is what the reply wants, not what was written. A reply longer than the first
        // guess is still held by the facade, so it is collected rather than regenerated —
        // generating again would add a second user turn to the conversation.
        val raw = if (needed < INITIAL_REPLY_BYTES) {
            buffer.toKString()
        } else {
            val size = needed + 1
            val grown = allocArray<ByteVar>(size)
            val collected = koilm_last_response(grown, size)
            check(collected == needed) {
                "LiteRT-LM reply changed size while being collected: $needed then $collected"
            }
            grown.toKString()
        }
        // No telemetry on this leg: litert_lm_session_get_benchmark_info() takes a Session and
        // the facade drives a Conversation, which the C API never lets you reach one from. A
        // zero here would be indistinguishable from a real measurement, so it stays null.
        GeneratedReply(extractResponseText(raw), telemetry = null)
    }

    override fun close() {
        koilm_session_free(handle)
    }
}
