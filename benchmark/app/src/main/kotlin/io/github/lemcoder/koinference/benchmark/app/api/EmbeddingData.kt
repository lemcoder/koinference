package io.github.lemcoder.koinference.benchmark.app.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One vector.
 *
 * [embedding] is a [JsonElement] because the wire type depends on `encoding_format`: an array of
 * numbers, or a base64 string of little-endian float32.
 */
@Serializable
data class EmbeddingData(
    val embedding: JsonElement,
    val index: Int,
    val `object`: String = "embedding",
)
