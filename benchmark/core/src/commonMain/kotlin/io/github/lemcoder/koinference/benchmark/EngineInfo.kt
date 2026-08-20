package io.github.lemcoder.koinference.benchmark

import kotlinx.serialization.Serializable

@Serializable
data class EngineInfo(
    val id: String,
    val version: String? = null,
    val modelId: String,
    val modelVersion: String,
    val quantization: String,
    val modelSha256: String? = null,
)
