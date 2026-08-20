package io.github.lemcoder.koinference.litertlm.internal

import io.github.lemcoder.koinference.Accelerator
import io.github.lemcoder.koinference.litertlm.jni.kniBridge0
import io.github.lemcoder.koinference.litertlm.jni.kniBridge1
import io.github.lemcoder.koinference.litertlm.jni.kniBridge10
import io.github.lemcoder.koinference.litertlm.jni.kniBridge11
import io.github.lemcoder.koinference.litertlm.jni.kniBridge2
import io.github.lemcoder.koinference.litertlm.jni.kniBridge4
import io.github.lemcoder.koinference.litertlm.jni.kniBridge5
import io.github.lemcoder.koinference.litertlm.jni.kniBridge6
import io.github.lemcoder.koinference.litertlm.jni.kniBridge7
import io.github.lemcoder.koinference.litertlm.jni.kniBridge8
import io.github.lemcoder.koinference.litertlm.jni.kniBridge9
import io.github.lemcoder.koinference.litertlm.jni.kniCString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

        val conversation = kniBridge4(handle, params.array(), options.systemPrompt)
        check(conversation != 0L) { "LiteRT-LM could not open a conversation: ${lastError()}" }
        return JniConversation(conversation)
    }

    override fun tokenCount(text: String): Int {
        val count = kniBridge11(handle, text)
        check(count >= 0) { "LiteRT-LM could not tokenize: ${lastError()}" }
        return count
    }

    override fun close() = kniBridge2(handle)
}
