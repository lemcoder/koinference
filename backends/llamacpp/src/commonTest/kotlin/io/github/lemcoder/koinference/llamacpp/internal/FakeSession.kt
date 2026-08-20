package io.github.lemcoder.koinference.llamacpp.internal

import io.github.lemcoder.koinference.Accelerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class FakeSession(
    val options: SessionOptions,
    private val reply: (String) -> String,
) : LlamaCppSession {

    val turns = mutableListOf<Turn>()
    var closed = false
        private set

    /** Runs inside [generate] and [stream], for tests that need to hold a generation open. */
    var whileGenerating: (() -> Unit)? = null

    /**
     * Chunks [stream] emits.
     *
     * The default cuts the canned reply into fixed-size pieces rather than words: they have to
     * concatenate back to exactly what [generate] returns, which is the property the streaming
     * contract promises, and splitting on spaces loses them.
     */
    var chunks: List<String>? = null

    /** Set when [stream] ran its `finally`, so an abandoned collection is observable. */
    var streamEnded = false
        private set

    override fun generate(systemPrompt: String?, prompt: String, grammar: String?): String {
        turns += Turn(systemPrompt, prompt, grammar)
        whileGenerating?.invoke()
        return reply(prompt)
    }

    override fun stream(systemPrompt: String?, prompt: String, grammar: String?): Flow<String> =
        flow {
            turns += Turn(systemPrompt, prompt, grammar)
            whileGenerating?.invoke()
            try {
                (chunks ?: reply(prompt).chunked(3)).forEach { emit(it) }
            } finally {
                streamEnded = true
            }
        }

    /** Whitespace words, which is enough for a fake: tests assert plumbing, not tokenization. */
    override fun tokenCount(text: String): Int = text.split(" ").count { it.isNotBlank() }

    /** Stands in for the big cluster the facade would find. */
    var mask: List<Int> = listOf(4, 5, 6, 7)
        private set

    val maskHistory = mutableListOf<List<Int>>()

    override fun cpuMask(): List<Int> = mask

    override fun setCpuMask(cpus: List<Int>) {
        mask = cpus
        maskHistory += cpus
    }

    override fun close() {
        closed = true
    }

    data class Turn(val systemPrompt: String?, val prompt: String, val grammar: String?)
}
