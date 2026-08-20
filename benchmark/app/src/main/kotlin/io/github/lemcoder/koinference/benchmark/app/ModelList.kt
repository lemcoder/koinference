package io.github.lemcoder.koinference.benchmark.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelList(
    @SerialName("object") val objectType: String = "list",
    val data: List<ModelCard>,
)
