package io.github.lemcoder.koinference.benchmark.config

import io.github.lemcoder.koinference.benchmark.result.RagMode

import kotlinx.serialization.Serializable

@Serializable
data class WorkloadConfig(
    val promptId: String,
    val maxNewTokens: Int,
    /** Whether this workload retrieves context before generating. */
    val ragMode: RagMode = RagMode.OFF,
    /** Passages to retrieve when [ragMode] is ON; ignored otherwise. */
    val ragK: Int = 0,
)
