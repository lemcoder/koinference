package io.github.lemcoder.koinference.benchmark.engine

import kotlinx.coroutines.flow.Flow

/**
 * One generation to perform.
 *
 * Identical for every engine, which is what makes results comparable. An engine that cannot
 * honour a field says so in its metadata rather than substituting its own value.
 */
data class GenerationRequest(
    val promptId: String,
    val prompt: String,
    val maxNewTokens: Int,
)
