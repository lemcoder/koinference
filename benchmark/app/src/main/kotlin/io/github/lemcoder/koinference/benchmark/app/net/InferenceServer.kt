package io.github.lemcoder.koinference.benchmark.app.net

import io.github.lemcoder.koinference.benchmark.app.api.ApiError
import io.github.lemcoder.koinference.benchmark.app.api.ApiErrorBody
import io.github.lemcoder.koinference.benchmark.app.api.ChatChoice
import io.github.lemcoder.koinference.benchmark.app.api.ChatCompletionChunk
import io.github.lemcoder.koinference.benchmark.app.api.ChatCompletionRequest
import io.github.lemcoder.koinference.benchmark.app.api.ChatCompletionResponse
import io.github.lemcoder.koinference.benchmark.app.api.ChatMessage
import io.github.lemcoder.koinference.benchmark.app.api.EmbeddingData
import io.github.lemcoder.koinference.benchmark.app.api.EmbeddingInput
import io.github.lemcoder.koinference.benchmark.app.api.EmbeddingRequest
import io.github.lemcoder.koinference.benchmark.app.api.EmbeddingResponse
import io.github.lemcoder.koinference.benchmark.app.api.EmbeddingUsage
import io.github.lemcoder.koinference.benchmark.app.api.ModelCard
import io.github.lemcoder.koinference.benchmark.app.api.ModelList
import io.github.lemcoder.koinference.benchmark.platform.platformProbe
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * An OpenAI-compatible server in front of one loaded model.
 *
 * Compatible on purpose: the point is to run Python benchmark clients that already exist
 * against a model on a phone, so the protocol has to be one they already speak.
 *
 * **This binds every interface with no authentication.** Anyone who can reach the device can
 * drive the model and read its output. That is a deliberate choice for a benchmark device on a
 * lab network — pass `bindAddress = "127.0.0.1"` and use `adb forward` when it is not.
 */
class InferenceServer(
    private val model: ServedBackend,
    private val port: Int,
    private val bindAddress: String,
    private val maxNewTokens: Int,
    private val onLog: (String) -> Unit = {},
) {

    private val scope = CoroutineScope(SupervisorJob())
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        // kotlinx omits properties left at their default, which silently drops OpenAI's discriminator
        // fields: `"object": "list"` and `"object": "embedding"` have defaults, so without this the
        // response is missing them and a client that validates the shape rejects it.
        encodeDefaults = true
    }

    private val engine = embeddedServer(CIO, port = port, host = bindAddress) {
        install(ContentNegotiation) { json(json) }

        routing {
            get("/healthz") {
                call.respond(mapOf("status" to "ok", "engine" to model.engineId))
            }

            get("/v1/models") {
                call.respond(
                    ModelList(
                        data = listOf(
                            ModelCard(
                                id = model.modelId,
                                created = epochSeconds(),
                                engine = model.engineId,
                                path = model.modelPath,
                            ),
                        ),
                    ),
                )
            }

            // Reuses the harness's own probe, so a run driven over HTTP describes its device
            // exactly the way an on-device run does — same fields, same nulls where a reading is
            // genuinely unavailable.
            get("/koinference/device") {
                call.respond(platformProbe().describeDevice())
            }

            // Not OpenAI's, and the reason this process exists on its own: read from inside the
            // inference process, these numbers describe the model and the engine and nothing else.
            get("/koinference/memory") {
                // Passed through as the engine process wrote it, rather than re-read here: this
                // process holds Compose and this server, and its PSS describes neither the model
                // nor the engine.
                call.respondText(model.processMemory(), ContentType.Application.Json)
            }

            // OpenAI's embeddings endpoint, so a RAG harness written against OpenAI or OpenRouter
            // can be pointed at a phone by changing base_url and nothing else.
            post("/v1/embeddings") {
                val request = runCatching { call.receive<EmbeddingRequest>() }.getOrElse { failure ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        error("Could not parse the request: ${failure.message}", "invalid_request_error"),
                    )
                    return@post
                }

                val texts = runCatching { EmbeddingInput.texts(request.input) }.getOrElse { failure ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        error(failure.message ?: "Unsupported input", "invalid_request_error"),
                    )
                    return@post
                }

                val (flat, width, tokens) = runCatching { model.embed(texts) }.getOrElse { failure ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        error(failure.message ?: "Embedding failed", "invalid_request_error"),
                    )
                    return@post
                }

                // OpenAI's newer models truncate to a requested width; ours cannot, so asking for one
                // we do not have is refused rather than answered with a differently-sized vector.
                if (request.dimensions != null && request.dimensions != width) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        error(
                            "This model embeds to $width dimensions and cannot truncate to " +
                                "${request.dimensions}",
                            "invalid_request_error",
                        ),
                    )
                    return@post
                }

                val base64 = request.encodingFormat.equals("base64", ignoreCase = true)
                val data = texts.indices.map { row ->
                    val vector = flat.copyOfRange(row * width, (row + 1) * width)
                    EmbeddingData(
                        embedding = if (base64) JsonPrimitive(encodeBase64(vector)) else numbers(vector),
                        index = row,
                    )
                }

                call.respond(
                    EmbeddingResponse(
                        data = data,
                        model = model.modelId,
                        usage = EmbeddingUsage(promptTokens = tokens, totalTokens = tokens),
                    ),
                )
            }

            post("/v1/chat/completions") {
                val request = runCatching { call.receive<ChatCompletionRequest>() }.getOrElse { failure ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        error("Could not parse the request: ${failure.message}", "invalid_request_error"),
                    )
                    return@post
                }

                val prompt = runCatching { request.flattenPrompt() }.getOrElse { failure ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        error(failure.message ?: "Unsupported request", "invalid_request_error"),
                    )
                    return@post
                }

                val schema = request.responseFormat?.jsonSchema?.schema?.toString()
                val requested = request.maxCompletionTokens ?: request.maxTokens
                if (requested != null && requested != maxNewTokens) {
                    // Said out loud rather than silently ignored: both backends fix the output
                    // limit when the model is loaded, so honouring this per request would mean
                    // reloading the model mid-benchmark.
                    onLog(
                        "request asked for max_tokens=$requested; serving with the loaded " +
                            "limit of $maxNewTokens (both engines fix it at load time)",
                    )
                }

                // An embedding model behind this endpoint is a caller mistake, not a server fault,
                // and it has to answer as one: an exception escaping the route closes the connection
                // with no body, which a client reports as a network error rather than a 400.
                try {
                    if (request.stream) {
                        streamCompletion(prompt, schema)
                    } else {
                        completeOnce(prompt, schema)
                    }
                } catch (failure: Throwable) {
                    onLog("chat completion failed: ${failure.message}")
                    call.respond(
                        HttpStatusCode.BadRequest,
                        error(failure.message ?: "Generation failed", "invalid_request_error"),
                    )
                }
            }
        }
    }

    fun start() {
        engine.start(wait = false)
        onLog("listening on $bindAddress:$port — model ${model.modelId} on ${model.engineId}")
    }

    fun stop() {
        engine.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
    }

    private suspend fun io.ktor.server.routing.RoutingContext.completeOnce(
        prompt: String,
        schema: String?,
    ) {
        val text = StringBuilder()
        model.stream(prompt, schema).collect { text.append(it) }
        call.respond(
            ChatCompletionResponse(
                id = completionId(),
                created = epochSeconds(),
                model = model.modelId,
                choices = listOf(
                    ChatChoice(
                        message = ChatMessage(role = "assistant", content = text.toString()),
                        finishReason = "stop",
                    ),
                ),
            ),
        )
    }

    /**
     * Server-sent events, flushed per chunk.
     *
     * The flush matters more than anything else here: a client measuring time to first token is
     * measuring when this write reaches it, so a buffered response would report the server's
     * buffering strategy rather than the model's latency.
     */
    private suspend fun io.ktor.server.routing.RoutingContext.streamCompletion(
        prompt: String,
        schema: String?,
    ) {
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            val id = completionId()

            model.stream(prompt, schema).collect { chunk ->
                val payload = ChatCompletionChunk(
                    id = id,
                    created = epochSeconds(),
                    model = model.modelId,
                    choices = listOf(ChatChoice(delta = ChatMessage(role = "assistant", content = chunk))),
                )
                write("data: ${json.encodeToString(ChatCompletionChunk.serializer(), payload)}\n\n")
                flush()
            }

            val done = ChatCompletionChunk(
                id = id,
                created = epochSeconds(),
                model = model.modelId,
                choices = listOf(ChatChoice(delta = ChatMessage(role = "assistant"), finishReason = "stop")),
            )
            write("data: ${json.encodeToString(ChatCompletionChunk.serializer(), done)}\n\n")
            write("data: [DONE]\n\n")
            flush()
        }
    }

    private fun numbers(vector: FloatArray): JsonArray =
        JsonArray(vector.map { JsonPrimitive(it) })

    /**
     * Little-endian float32, base64 — the layout OpenAI's own responses use.
     *
     * Not an optimisation: the official Python client requests `base64` by default when numpy is
     * present, so a server that only speaks floats fails against the very clients this endpoint
     * exists for.
     */
    private fun encodeBase64(vector: FloatArray): String {
        val bytes = java.nio.ByteBuffer.allocate(vector.size * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        vector.forEach { bytes.putFloat(it) }
        return java.util.Base64.getEncoder().encodeToString(bytes.array())
    }

    private fun error(message: String, type: String) = ApiError(ApiErrorBody(message, type))

    private fun completionId(): String = "chatcmpl-" + android.os.Process.myPid() + "-" + System.nanoTime()

    private fun epochSeconds(): Long = System.currentTimeMillis() / 1000
}

private val SUPPORTED_ROLES = setOf("system", "user", "assistant")

/**
 * Flattens a chat request into the single prompt string both backends take.
 *
 * A system message becomes the runtime's system prompt only if the model was loaded with one,
 * which it is not here, so it is prepended instead — LFM2.5 rejects a system role outright, and
 * a benchmark server that fails on `messages[0].role == "system"` would be useless.
 */
internal fun ChatCompletionRequest.flattenPrompt(): String {
    require(messages.isNotEmpty()) { "messages must not be empty" }

    return messages.joinToString("\n\n") { message ->
        require(message.role in SUPPORTED_ROLES) {
            "Unsupported message role '${message.role}'; expected one of $SUPPORTED_ROLES"
        }
        // Every supported role contributes its text the same way — a system message is prepended
        // rather than set as the runtime's system prompt, since the model here was not loaded
        // with one.
        requireNotNull(message.content) {
            "message content must be a string; content arrays are not supported because both " +
                "backends are text-only"
        }
    }
}
