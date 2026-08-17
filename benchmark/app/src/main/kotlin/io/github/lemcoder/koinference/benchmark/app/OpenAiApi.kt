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

@Serializable
data class ResponseFormat(
    val type: String,
    @SerialName("json_schema") val jsonSchema: JsonSchemaSpec? = null,
)

@Serializable
data class JsonSchemaSpec(
    val name: String? = null,
    val strict: Boolean? = null,
    /** The schema itself, passed through to the backend's constrained decoding as written. */
    val schema: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<ChatChoice>,
)

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatMessage? = null,
    val delta: ChatMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChatChoice>,
)

@Serializable
data class ModelList(
    @SerialName("object") val objectType: String = "list",
    val data: List<ModelCard>,
)

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

@Serializable
data class ApiError(val error: ApiErrorBody)

@Serializable
data class ApiErrorBody(
    val message: String,
    val type: String,
    val code: String? = null,
)

/**
 * What the inference process is costing.
 *
 * Not an OpenAI endpoint — `/koinference/memory` — and the reason the service runs in its own
 * process at all: read from inside that process, these numbers describe the model and the
 * engine, with no Activity, no HTTP stack and no test runner mixed in.
 */
@Serializable
data class ProcessMemory(
    val pid: Int,
    val processName: String,
    val pssKb: Long?,
    val rssKb: Long?,
    val nativeHeapKb: Long?,
    val javaHeapKb: Long?,
    val engine: String?,
    val modelPath: String?,
    val modelLoadMs: Double?,
)
