package io.github.lemcoder.koinference.cera.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class FakeCeraSession(
    val options: CeraSessionOptions,
    private val reply: (String) -> String,
) : CeraSession {

    val prompts = mutableListOf<String>()
    val grammars = mutableListOf<String?>()

    var closed = false
        private set

    override suspend fun generate(prompt: String, grammar: String?): String {
        prompts += prompt
        grammars += grammar
        return reply(prompt)
    }

    override fun stream(prompt: String, grammar: String?): Flow<String> = flow {
        prompts += prompt
        grammars += grammar
        // In pieces, because a binding that emitted one chunk would satisfy every other property
        // of streaming while making time to first token equal total latency.
        reply(prompt).chunked(3).forEach { emit(it) }
    }

    override fun close() {
        closed = true
    }
}
