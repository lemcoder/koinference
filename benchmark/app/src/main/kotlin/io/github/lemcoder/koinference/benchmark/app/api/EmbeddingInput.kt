package io.github.lemcoder.koinference.benchmark.app.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Widens OpenAI's four shapes of `input` into the one this server can serve.
 *
 * A string, or an array of strings. The two token-id forms are refused by name rather than
 * mis-served: this backend tokenizes with the model's own vocabulary, and ids from somebody else's
 * tokenizer would embed as nonsense while looking like a successful request.
 */
object EmbeddingInput {

    fun texts(input: JsonElement): List<String> = when (input) {
        is JsonPrimitive -> {
            require(input.isString) { "input must be a string or an array of strings" }
            listOf(input.content)
        }

        is JsonArray -> {
            require(input.isNotEmpty()) { "input must not be empty" }
            input.map { element ->
                val primitive = (element as? JsonPrimitive)
                    ?: error("input arrays of token ids are not supported; send text")
                require(primitive.isString) {
                    "input arrays of token ids are not supported; send text"
                }
                primitive.content
            }
        }

        else -> error("input must be a string or an array of strings")
    }
}
