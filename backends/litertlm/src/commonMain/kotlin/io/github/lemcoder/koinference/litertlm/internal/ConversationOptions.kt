package io.github.lemcoder.koinference.litertlm.internal

import io.github.lemcoder.koinference.runtime.GenerationParameters
import io.github.lemcoder.koinference.runtime.Accelerator
import kotlinx.coroutines.flow.Flow

internal data class ConversationOptions(
    val systemPrompt: String? = null,
    /** Max tokens per reply; 0 uses the engine's budget. */
    val maxTokens: Int = 0,
    val topK: Int = DEFAULT_TOP_K,
    val topP: Float = DEFAULT_TOP_P,
    val temperature: Float = DEFAULT_TEMPERATURE,
    /** null leaves the runtime's own seeding, which differs between the two legs. */
    val seed: Int? = null,
    /**
     * Take the most likely token every step, ignoring [topK], [topP] and [temperature].
     *
     * Decided here rather than by each leg, because LiteRT-LM's sampler type is fixed when the
     * conversation opens and temperature 0 does *not* select it: a top-p sampler asked for
     * temperature 0 still samples, and produces a different answer to the same question. The
     * Apple leg maps this onto the runtime's greedy sampler; Android has no sampler type in its
     * public config, so it uses top-k of 1, which is argmax by another name.
     */
    val greedy: Boolean = false,
)
