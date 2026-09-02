package io.github.lemcoder.koinference.executorch.internal

/**
 * What one generation needs.
 *
 * Sparse next to the other backends' options, and honestly so: `LlmGenerationConfig` offers
 * temperature, sequence length and token budget, and no top-k, top-p, min-p or seed. What is not
 * here is not ignored silently — see `ExecuTorch.honours`.
 */
internal data class ExecuTorchSessionOptions(
    val maxOutputTokens: Int,
    val contextTokens: Int,
    val temperature: Double,
)
