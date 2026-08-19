package io.github.lemcoder.koinference.litertlm.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private val json = Json { ignoreUnknownKeys = true }

/**
 * Pull the assistant text out of a LiteRT-LM reply.
 *
 * The runtime answers with a message object rather than bare text:
 *
 * ```json
 * {"role":"assistant","content":[{"type":"text","text":"Hello."}]}
 * ```
 *
 * `content` is an array because a reply can interleave several parts; only text parts
 * carry generated tokens, and they are concatenated in order. A schema-constrained reply
 * arrives the same way, with the JSON document itself sitting inside `text` as a string.
 *
 * Returns an empty string for a reply that is empty, malformed, or carries no text part —
 * the facade already returns "" on failure, so callers have one shape to handle.
 */
internal fun extractResponseText(raw: String): String {
    if (raw.isEmpty()) return ""

    val message = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return ""

    return when (val content = message["content"]) {
        is JsonPrimitive -> content.contentOrEmpty()
        // as?, not jsonPrimitive: the accessor throws when the value is an object or an
        // array, which would turn an envelope this function is meant to tolerate into an
        // exception out of generateResponse.
        is JsonArray -> content.joinToString("") { part ->
            val partObject = part as? JsonObject ?: return@joinToString ""
            val type = partObject["type"] as? JsonPrimitive
            if (type?.contentOrEmpty() != "text") return@joinToString ""
            (partObject["text"] as? JsonPrimitive)?.contentOrEmpty().orEmpty()
        }
        else -> ""
    }
}

private fun JsonPrimitive.contentOrEmpty(): String = if (isString) content else ""
