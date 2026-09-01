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
        return reply(prompt)
    }

    override fun stream(prompt: String): Flow<String> = flow {
        prompts += prompt
        // In pieces: a binding that emitted one chunk would satisfy every other property of
        // streaming while making time to first token equal total latency.
        reply(prompt).chunked(3).forEach { emit(it) }
    }

    override fun cancel() {
        cancelled = true
    }

    override fun close() {
        closed = true
    }
}
