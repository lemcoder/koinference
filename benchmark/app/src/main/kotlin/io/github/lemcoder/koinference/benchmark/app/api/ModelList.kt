package io.github.lemcoder.koinference.benchmark.app.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelList(
    @SerialName("object") val objectType: String = "list",
    val data: List<ModelCard>,
)
