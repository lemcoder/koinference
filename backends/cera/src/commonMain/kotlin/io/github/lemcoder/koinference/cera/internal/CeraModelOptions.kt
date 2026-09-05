package io.github.lemcoder.koinference.cera.internal

import io.github.lemcoder.koinference.runtime.Accelerator

/**
 * What loading weights needs.
 *
 * The engine's vocabulary, not [io.github.lemcoder.koinference.backend.ModelConfig]'s: mapping
 * between the two happens once, in the loader.
 */
internal data class CeraModelOptions(
    val modelPath: String,
    val accelerator: Accelerator,
    val contextTokens: Int,
)
