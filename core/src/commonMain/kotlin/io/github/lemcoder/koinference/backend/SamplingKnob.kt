package io.github.lemcoder.koinference.backend

import io.github.lemcoder.koinference.runtime.GenerationParameters

/** The individually supported sampling knobs of [GenerationParameters]. */
enum class SamplingKnob {
    TOP_K,
    TOP_P,
    MIN_P,
    TEMPERATURE,
    SEED,
}
