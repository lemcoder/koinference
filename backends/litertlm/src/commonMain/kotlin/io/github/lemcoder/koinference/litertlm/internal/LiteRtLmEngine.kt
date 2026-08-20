package io.github.lemcoder.koinference.litertlm.internal

import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.Accelerator
import kotlinx.coroutines.flow.Flow

/** An engine with a model loaded into it. */
internal interface LiteRtLmEngine {
    fun openConversation(options: ConversationOptions): LiteRtLmConversation

    /**
     * Tokens in [text], according to the model's own tokenizer.
     *
     * On the engine rather than the conversation because that is where the C API puts it, and
     * because it is a property of the model rather than of a turn.
     */
    fun tokenCount(text: String): Int

    /** Releases the model. Calling anything on the engine afterwards is undefined. */
    fun close()
}
