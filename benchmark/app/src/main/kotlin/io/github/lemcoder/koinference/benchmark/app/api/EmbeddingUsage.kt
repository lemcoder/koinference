package io.github.lemcoder.koinference.benchmark.app.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tokens the request cost.
 *
 * Counted with the model's own vocabulary, not estimated from characters: a RAG harness comparing
 * this against OpenAI is comparing these numbers too.
 */
@Serializable
data class EmbeddingUsage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
)
