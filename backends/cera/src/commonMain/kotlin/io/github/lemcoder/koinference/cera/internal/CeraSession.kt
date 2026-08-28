package io.github.lemcoder.koinference.cera.internal

import kotlinx.coroutines.flow.Flow

/** One conversation over a [CeraModel]. */
internal interface CeraSession {

    /** The whole reply. Drains the same decode loop [stream] pulls from, so there is one path. */
    suspend fun generate(prompt: String, grammar: String?): String

    /** Reply text as it is decoded. */
    fun stream(prompt: String, grammar: String?): Flow<String>

    fun close()
}
