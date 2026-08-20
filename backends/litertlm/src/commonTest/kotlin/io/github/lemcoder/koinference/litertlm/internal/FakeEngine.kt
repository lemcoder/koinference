package io.github.lemcoder.koinference.litertlm.internal

import io.github.lemcoder.koinference.Accelerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class FakeEngine(val options: EngineOptions) : LiteRtLmEngine {

    val conversations = mutableListOf<FakeConversation>()
    var closed = false
        private set

    val conversation: FakeConversation get() = conversations.last()

    /** Whitespace words, which is enough for a fake: tests assert plumbing, not tokenization. */
    override fun tokenCount(text: String): Int = text.split(" ").count { it.isNotBlank() }

    override fun openConversation(options: ConversationOptions): LiteRtLmConversation =
        FakeConversation(options).also { conversations += it }

    override fun close() {
        closed = true
    }
}
