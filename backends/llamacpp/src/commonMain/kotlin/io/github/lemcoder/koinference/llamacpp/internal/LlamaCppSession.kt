package io.github.lemcoder.koinference.llamacpp.internal

import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.Accelerator
import kotlinx.coroutines.flow.Flow

/** One session over a model, owning the KV cache, the batch and the sampler. */
internal interface LlamaCppSession {

    /** Generate one reply and wait for it (blocking). */
    fun generate(systemPrompt: String?, prompt: String, grammar: String?): String

    /**
     * Stream the reply, one chunk per emission.
     *
     * A chunk is one sampled token: the facade's pull loop returns one per call. Whoever is
     * timing decides when each one arrived — this hands back chunks and nothing else.
     */
    fun stream(systemPrompt: String?, prompt: String, grammar: String?): Flow<String>

    /** Tokens in [text] by the model's own vocabulary. */
    fun tokenCount(text: String): Int

    /** CPUs the decode threads are pinned to, ascending; empty for default placement. */
    fun cpuMask(): List<Int>

    /**
     * Re-pin the decode threads. Empty restores default placement.
     *
     * Only safe between decodes — the pool is in use during one — which the runtime guarantees by
     * holding its guard.
     */
    fun setCpuMask(cpus: List<Int>)

    fun close()
}
