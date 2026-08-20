package io.github.lemcoder.koinference.benchmark.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,
    /**
     * Text only. OpenAI allows an array of content parts here; a request that sends one is
     * rejected rather than silently flattened, because the backends are text-only too and a
     * dropped image would look like a bad model rather than an unsupported request.
     */
    val content: String? = null,
)
