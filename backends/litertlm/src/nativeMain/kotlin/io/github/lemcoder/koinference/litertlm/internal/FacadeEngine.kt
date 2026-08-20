@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.litertlm.internal

import cnames.structs.KoiLmConversation
import cnames.structs.KoiLmEngine
import io.github.lemcoder.koinference.runtime.Accelerator
// The cinterop package is named after the .def file, not the interop, so it is
// koinference_litertlm rather than koinferenceLiteRtLm.
import koinference_litertlm.KOILM_BACKEND_CPU
import koinference_litertlm.KOILM_BACKEND_GPU
import koinference_litertlm.KoiLmSessionParams
import koinference_litertlm.koilm_stream_begin
import koinference_litertlm.koilm_token_count
import koinference_litertlm.koilm_stream_next
import koinference_litertlm.koilm_stream_end
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.cinterop.toKString

internal class FacadeEngine(private val handle: CPointer<KoiLmEngine>) : LiteRtLmEngine {

    override fun openConversation(options: ConversationOptions): LiteRtLmConversation {
        // koilm_default_session_params() returns a CValue — an immutable off-heap snapshot —
        // so its fields cannot be assigned through it. Every field is set here anyway.
        val params = cValue<KoiLmSessionParams> {
            max_tokens = options.maxTokens
            top_k = options.topK
            this.top_p = options.topP // qualified: unqualified top_p is the struct field's setter argument
            this.temp = options.temperature
            seed = options.seed ?: UNSEEDED
            greedy = if (options.greedy) 1 else 0
        }
        val conversation = koilm_session_create(handle, params, options.systemPrompt)
        checkNotNull(conversation) { "Could not open a LiteRT-LM conversation: ${lastError()}" }
        return FacadeConversation(conversation)
    }

    override fun tokenCount(text: String): Int {
        val count = koilm_token_count(handle, text)
        check(count >= 0) { "LiteRT-LM could not tokenize: ${lastError()}" }
        return count
    }

    override fun close() {
        koilm_model_free(handle)
    }
}
