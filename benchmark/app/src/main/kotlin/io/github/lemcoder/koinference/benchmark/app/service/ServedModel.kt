package io.github.lemcoder.koinference.benchmark.app.service

import io.github.lemcoder.koinference.Koinference
import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.benchmark.app.IGenerationCallback
import io.github.lemcoder.koinference.runtime.Accelerator
import io.github.lemcoder.koinference.runtime.EmbeddingRuntime
import io.github.lemcoder.koinference.runtime.GenerationConstraint
import io.github.lemcoder.koinference.runtime.GeneratingRuntime
import io.github.lemcoder.koinference.runtime.ModelRuntime
import io.github.lemcoder.koinference.runtime.text.TokenCounting
import io.github.lemcoder.koinference.runtime.GenerationParameters
import io.github.lemcoder.koinference.runtime.ResponsePart
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A model loaded for serving, as opposed to for a benchmark run.
 *
 * The whole integration with the library is this file: register the backend this process owns, ask
 * for a path, stream the reply. It does not know what llama.cpp or LiteRT-LM are.
 */
class ServedModel private constructor(
    private val koi: Koinference,
    private val runtime: ModelRuntime,
    private val modelPath: String,
    private val loadMs: Double,
) {

    /** Vectors, when the loaded model embeds; null when it generates. */
    private val embedder: EmbeddingRuntime? = runtime as? EmbeddingRuntime

    /** Replies, when the loaded model generates; null when it embeds. */
    private val generator: GeneratingRuntime? = runtime as? GeneratingRuntime

    /** What this model does, for `/v1/models` and for refusing the wrong request clearly. */
    val kind: String = if (embedder != null) "embedding" else "generation"

    val modelId: String = File(modelPath).nameWithoutExtension

    fun describe(): String = buildJsonObject {
        put("modelId", modelId)
        put("modelPath", modelPath)
        put("modelLoadMs", loadMs)
    }.toString()

    /**
     * Streams the reply text to [callback].
     *
     * Only [ResponsePart.Text] is sent. A reply can carry audio, and an OpenAI-compatible `delta`
     * has nowhere to put it — the drop happens here, where it is visible, rather than behind a
     * convenience on the runtime.
     */
    /**
     * Embeds [texts], returning the vectors flattened with their width.
     *
     * Flat because that is how they cross the binder; see `IEmbeddingCallback`.
     */
    suspend fun embed(texts: List<String>): Triple<FloatArray, Int, Int> {
        val embedding = embedder
            ?: error("$modelId generates text; it has no embeddings to give")

        val vectors = embedding.embed(texts)
        val width = embedding.dimensions
        val flat = FloatArray(vectors.size * width)
        vectors.forEachIndexed { row, vector -> vector.copyInto(flat, row * width) }

        // The model's own tokenizer where it has one, so `usage` means what OpenAI's means rather
        // than being a character count wearing a token's name.
        val tokens = (embedding as? TokenCounting)?.let { counter ->
            texts.sumOf { counter.countTokens(it) }
        } ?: 0

        return Triple(flat, width, tokens)
    }

    suspend fun generate(requestJson: String, callback: IGenerationCallback) {
        val generating = generator ?: error("$modelId embeds text; it has no reply to generate")
        val request = Json.parseToJsonElement(requestJson).jsonObject
        val prompt = request["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val schema = request["schema"]?.jsonPrimitive?.contentOrNull

        var chunks = 0
        val text = StringBuilder()
        generating.streamResponse(prompt, schema?.let { GenerationConstraint.JsonSchema(it) })
            .collect { part ->
                if (part is ResponsePart.Text) {
                    chunks++
                    text.append(part.text)
                    callback.onChunk(part.text)
                }
            }

        callback.onFinished(
            buildJsonObject {
                put("chunks", chunks)
                put("outputChars", text.length)
            }.toString(),
        )
    }

    suspend fun unload() = koi.unloadAll()

    companion object {

        suspend fun load(
            backend: Backend,
            modelPath: String,
            options: Map<String, String>,
            cacheDir: String,
        ): ServedModel {
            require(File(modelPath).isFile) { "no model file at $modelPath" }

            val koi = Koinference(
                backend,
                config = ModelConfig(
                    settings = RuntimeSettings(
                        accelerator = if (options["gpu"].toBoolean()) Accelerator.GPU else Accelerator.CPU,
                    ),
                    parameters = GenerationParameters(
                        temperature = options["temperature"]?.toDoubleOrNull() ?: 0.0,
                        seed = options["seed"]?.toIntOrNull() ?: 42,
                    ),
                    contextTokens = options["contextTokens"]?.toIntOrNull() ?: 0,
                    maxOutputTokens = options["maxNewTokens"]?.toIntOrNull() ?: 256,
                    threads = options["threads"]?.toIntOrNull() ?: 0,
                    cacheDir = cacheDir,
                ),
            )

            val start = System.nanoTime()
            // The base ModelRuntime: which kind it is decides what this can serve, and the caller
            // asking for the wrong one is told rather than given nonsense.
            val runtime: ModelRuntime = koi.load(modelPath)
            val loadMs = (System.nanoTime() - start) / 1_000_000.0

            return ServedModel(koi, runtime, modelPath, loadMs)
        }
    }
}
