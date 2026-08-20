package io.github.lemcoder.koinference.benchmark.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatMessage? = null,
    val delta: ChatMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)
