package io.github.lemcoder.koinference.benchmark.app.api

import kotlinx.serialization.Serializable

/** OpenAI's embeddings response. */
@Serializable
data class EmbeddingResponse(
    val data: List<EmbeddingData>,
    val model: String,
    val usage: EmbeddingUsage,
    val `object`: String = "list",
)
