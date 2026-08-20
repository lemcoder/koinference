package io.github.lemcoder.koinference.benchmark

import kotlinx.serialization.Serializable

@Serializable
data class WorkloadInfo(
    val promptId: String,
    val promptSha256: String? = null,
    /** Characters, not tokens: the harness has no tokenizer of its own and will not guess. */
    val promptChars: Int,
    val maxNewTokens: Int,
)
