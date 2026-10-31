package io.github.lemcoder.koinference.litertlm.internal

import io.github.lemcoder.koinference.runtime.generation.Accelerator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import koinference_litertlm.koilm_model_free
import koinference_litertlm.koilm_session_create
import koinference_litertlm.koilm_token_count
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

internal class JniEngine(private val handle: Long) : LiteRtLmEngine {

    override fun openConversation(options: ConversationOptions): LiteRtLmConversation {
        // Packed by hand for the same reason the llama.cpp bridge does it: the generator marshals
        // a by-value struct as a byte array, so the field order in the header is the contract.
        val params = ByteBuffer.allocate(SESSION_PARAMS_SIZE).order(ByteOrder.nativeOrder())
            .putInt(options.maxTokens)
            .putInt(options.topK)
            .putFloat(options.topP)
            .putFloat(options.temperature)
            .putInt(options.seed ?: UNSEEDED)
            .putInt(if (options.greedy) 1 else 0)

        val conversation = koilm_session_create(handle, params.array(), options.systemPrompt)
        check(conversation != 0L) { "LiteRT-LM could not open a conversation: ${lastError()}" }
        return JniConversation(conversation)
    }

    override fun tokenCount(text: String): Int {
        val count = koilm_token_count(handle, text)
        check(count >= 0) { "LiteRT-LM could not tokenize: ${lastError()}" }
        return count
    }

    override fun close() = koilm_model_free(handle)
}
