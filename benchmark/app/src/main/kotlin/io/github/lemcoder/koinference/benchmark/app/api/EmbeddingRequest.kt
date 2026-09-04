package io.github.lemcoder.koinference.benchmark.app.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * OpenAI's embeddings request.
 *
 * `input` is deliberately a raw [JsonElement]: the API accepts a string, an array of strings, an
 * array of token ids, or an array of those. Declaring it as one type would reject clients that are
 * within spec — and the point of this endpoint is that existing clients work unchanged.
 * [EmbeddingInput] does the widening.
 */
@Serializable
data class EmbeddingRequest(
    val model: String? = null,
    val input: JsonElement,
    /**
     * `float` or `base64`.
     *
     * Worth honouring rather than ignoring: the official Python client asks for **base64 by
     * default** when numpy is installed, and a server that answers with floats regardless leaves it
     * decoding a list as if it were a string.
     */
    @SerialName("encoding_format") val encodingFormat: String? = null,
    /** OpenAI truncates its own models to this. Ours cannot, so a mismatch is an error, not a lie. */
    val dimensions: Int? = null,
    val user: String? = null,
)
