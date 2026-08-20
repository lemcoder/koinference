package io.github.lemcoder.koinference.benchmark.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The subset of OpenAI's API this server speaks.
 *
 * Chosen so that existing clients work unchanged — the `openai` Python package, curl, and the
 * load generators people already have — because a benchmark client written against a bespoke
 * protocol only measures the bespoke protocol.
 *
 * Deliberately absent: `usage`. Filling in token counts would mean counting streamed chunks and
 * calling them tokens, which is the one thing this project keeps refusing to do; a client that
 * needs throughput measures it from the stream, which is what a client experiences anyway.
 */
@Serializable
data class ChatCompletionRequest(
    val model: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    val seed: Int? = null,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
)
