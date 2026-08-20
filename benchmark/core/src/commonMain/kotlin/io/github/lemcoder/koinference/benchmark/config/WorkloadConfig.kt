package io.github.lemcoder.koinference.benchmark.config

import kotlinx.serialization.Serializable

@Serializable
data class WorkloadConfig(
    val promptId: String,
    val maxNewTokens: Int,
)
