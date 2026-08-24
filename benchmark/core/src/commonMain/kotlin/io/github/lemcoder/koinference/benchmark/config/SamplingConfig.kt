package io.github.lemcoder.koinference.benchmark.config

import kotlinx.serialization.Serializable

/**
 * Sampling knobs, applied identically to every engine.
 *
 * Defaults to greedy decoding: llama.cpp's facade exposes no seed, so temperature 0 is the
 * only setting that makes both engines reproducible in the same way. [seed] is still recorded
 * and passed to engines that have one.
 */
@Serializable
data class SamplingConfig(
    val temperature: Double = 0.0,
    val topK: Int? = null,
    val topP: Double? = null,
    val seed: Int = 42,
)
