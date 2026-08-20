package io.github.lemcoder.koinference.benchmark

import kotlinx.serialization.Serializable

@Serializable
data class WorkloadConfig(
    val promptId: String,
    val maxNewTokens: Int,
)
