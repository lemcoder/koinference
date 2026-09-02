package io.github.lemcoder.koinference.executorch.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class FakeExecuTorchSession(
    val options: ExecuTorchSessionOptions,
    private val reply: (String) -> String,
) : ExecuTorchSession {

    val prompts = mutableListOf<String>()

    var closed = false
        private set

    var cancelled = false
        private set

    var resets = 0
        private set

    override fun reset() {
        resets++
    }

    override suspend fun generate(prompt: String): String {
        prompts += prompt
        return reply(prompt).also { lastReply = it }
    }

    override fun stream(prompt: String): Flow<String> = flow {
        prompts += prompt
        // In pieces: a binding that emitted one chunk would satisfy every other property of
        // streaming while making time to first token equal total latency.
        val text = reply(prompt)
        lastReply = text
        text.chunked(3).forEach { emit(it) }
    }

    /** One token per whitespace word of the last reply; the point is that the runtime asks. */
    override fun generatedTokens(text: String): Int? =
        text.split(" ").count { it.isNotBlank() }.takeIf { text == lastReply }

    private var lastReply: String? = null

    override fun cancel() {
        cancelled = true
    }

    override fun close() {
        closed = true
    }
}
