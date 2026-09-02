package io.github.lemcoder.koinference.cera.internal

import kotlinx.coroutines.flow.Flow

/** One conversation over a [CeraModel]. */
internal interface CeraSession {

    /**
     * Drops everything appended so far, so the next turn starts from an empty context.
     *
     * A session accumulates: every `appendText` adds to the same conversation, and a generation
     * appends its own reply. Without this, the fourth identical prompt takes 6.7 s where the first
     * took 4.8 — the engine is re-prefilling a conversation, not answering a question.
     */
    fun reset()

    /** The whole reply. Drains the same decode loop [stream] pulls from, so there is one path. */
    suspend fun generate(prompt: String, grammar: String?): String

    /** Reply text as it is decoded. */
    fun stream(prompt: String, grammar: String?): Flow<String>

    fun close()
}
