package io.github.lemcoder.koinference.benchmark.app.service

import io.github.lemcoder.koinference.Koinference
import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.benchmark.app.IGenerationCallback
import io.github.lemcoder.koinference.prompt.promptOf
import io.github.lemcoder.koinference.runtime.Connection
import io.github.lemcoder.koinference.runtime.EmbeddingConnection
import io.github.lemcoder.koinference.runtime.GeneratingConnection
import io.github.lemcoder.koinference.runtime.generation.Accelerator
import io.github.lemcoder.koinference.runtime.generation.GenerationConstraint
import io.github.lemcoder.koinference.runtime.generation.GenerationParameters
import io.github.lemcoder.koinference.runtime.media.ResponsePart
import io.github.lemcoder.koinference.runtime.text.TokenCounting
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A model loaded for serving, as opposed to for a benchmark run.
 *
 * The whole integration with the library is this file: register the backend this process owns, load
 * a model, open one connection over it, stream the reply. It does not know what llama.cpp or
 * LiteRT-LM are.
 */
class ServedModel private constructor(
    private val koi: Koinference,
    connection: Connection,
    private val modelPath: String,
    private val loadMs: Double,
) {

    /** Vectors, when the loaded model embeds; null when it generates. */
    private val embedder: EmbeddingConnection? = connection as? EmbeddingConnection

    /** Replies, when the loaded model generates; null when it embeds. */
    private val generator: GeneratingConnection? = connection as? GeneratingConnection

    /** What this model does, for `/v1/models` and for refusing the wrong request clearly. */
    val kind: String = if (embedder != null) "embedding" else "generation"

    val modelId: String = File(modelPath).nameWithoutExtension

    fun describe(): String = buildJsonObject {
        put("modelId", modelId)
        put("modelPath", modelPath)
        put("modelLoadMs", loadMs)
    }.toString()

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

        val tokens = (embedding as? TokenCounting)?.let { counter ->
            texts.sumOf { counter.countTokens(it) }
        } ?: 0

        return Triple(flat, width, tokens)
    }

    /**
     * Streams the reply text to [callback].
     *
     * Only [ResponsePart.Text] is sent. A reply can carry audio, and an OpenAI-compatible `delta`
     * has nowhere to put it — the drop happens here, where it is visible.
     */
    suspend fun generate(requestJson: String, callback: IGenerationCallback) {
        val generating = generator ?: error("$modelId embeds text; it has no reply to generate")
        val request = Json.parseToJsonElement(requestJson).jsonObject
        val prompt = request["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val schema = request["schema"]?.jsonPrimitive?.contentOrNull

        var chunks = 0
        val text = StringBuilder()
        generating.generate(promptOf(prompt), schema?.let { GenerationConstraint.JsonSchema(it) }) { part ->
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
            // Load the weights, then open the one connection this serves over — which kind of
            // connection it is decides what this can serve, and the wrong request is told so.
            val connection = koi.openConnection(koi.loadModel(modelPath))
            val loadMs = (System.nanoTime() - start) / 1_000_000.0

            return ServedModel(koi, connection, modelPath, loadMs)
        }
    }
}
