package io.github.lemcoder.koinference.litertlm.internal

import io.github.lemcoder.koinference.runtime.Accelerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class FakeConversation(val options: ConversationOptions) : LiteRtLmConversation {

    val turns = mutableListOf<Turn>()
    var closed = false
        private set

    /** Runs inside [generate], for tests that need to hold a generation open. */
    var whileGenerating: (() -> Unit)? = null

    override fun generate(prompt: String, jsonSchema: String?): String {
        turns += Turn(prompt, jsonSchema)
        whileGenerating?.invoke()
        return "reply to $prompt"
    }

    /** Chunks [stream] emits; the default splits the canned reply so collectors see several. */
    var chunks: List<String>? = null

    override fun stream(prompt: String, jsonSchema: String?): Flow<String> = flow {
        turns += Turn(prompt, jsonSchema)
        whileGenerating?.invoke()
        (chunks ?: "reply to $prompt".split(" ").map { "$it " }).forEach { emit(it) }
    }

    override fun close() {
        closed = true
    }

    data class Turn(val prompt: String, val jsonSchema: String?)
}
