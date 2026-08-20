package io.github.lemcoder.koinference.benchmark.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelCard(
    val id: String,
    @SerialName("object") val objectType: String = "model",
    val created: Long,
    @SerialName("owned_by") val ownedBy: String = "koinference",
    /** Not OpenAI's, and prefixed so nobody mistakes it for a field their client knows. */
    @SerialName("koinference_engine") val engine: String,
    @SerialName("koinference_path") val path: String,
)
