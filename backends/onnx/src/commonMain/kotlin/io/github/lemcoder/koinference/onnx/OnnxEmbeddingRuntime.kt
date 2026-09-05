package io.github.lemcoder.koinference.onnx

import io.github.lemcoder.koinference.onnx.internal.ModelFiles
import io.github.lemcoder.koinference.onnx.internal.OnnxBridge
import io.github.lemcoder.koinference.onnx.internal.OnnxModel
import io.github.lemcoder.koinference.onnx.internal.OnnxModelOptions
import io.github.lemcoder.koinference.onnx.internal.Pooling
import io.github.lemcoder.koinference.onnx.internal.PoolingMode
import io.github.lemcoder.koinference.onnx.internal.TokenBatch
import io.github.lemcoder.koinference.onnx.internal.WordPiece
import io.github.lemcoder.koinference.runtime.generation.Accelerator
import io.github.lemcoder.koinference.runtime.EmbeddingRuntime
import io.github.lemcoder.koinference.runtime.generation.GenerationParameters
import io.github.lemcoder.koinference.runtime.RuntimeGuard
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import io.github.lemcoder.koinference.runtime.text.TokenCounting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A loaded encoder, turning text into vectors.
 *
 * The first [EmbeddingRuntime] in this repository. It also counts tokens, because it has the
 * vocabulary in hand and a caller batching documents needs to know what will fit.
 *
 * Vectors are **L2-normalised**, so a dot product is a cosine similarity — what every vector store
 * assumes, and what the OpenAI embeddings this is meant to be compared against already are.
 */
class OnnxEmbeddingRuntime internal constructor(
    private val model: OnnxModel,
    private val vocabulary: Map<String, Int>,
    private val poolingMode: PoolingMode,
    private val maxTokens: Int,
    private val modelPath: String,
    parameters: GenerationParameters = GenerationParameters(),
) : EmbeddingRuntime, TokenCounting {

    override val dimensions: Int get() = model.hiddenSize

    override var generationParameters: GenerationParameters = parameters
        private set

    /** Where ONNX Runtime placed the graph; this backend does not offer a device choice yet. */
    override val runtimeSettings: RuntimeSettings get() = RuntimeSettings(Accelerator.CPU)

    private val guard = RuntimeGuard { modelPath }

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()

        // Tokenised outside the lock: it is pure Kotlin and should not queue behind someone else's
        // inference.
        val encoded = texts.map { WordPiece.encode(it, vocabulary, maxTokens) }
        val batch = batchOf(encoded)

        return guard.whileOpen {
            withContext(Dispatchers.Default) {
                val hidden = model.run(batch)
                hidden.mapIndexed { row, tokens ->
                    Pooling.normalise(Pooling.pool(tokens, batch.attentionMask[row], poolingMode))
                }
            }
        }
    }

    /**
     * Tokens this model would read in [text], including `[CLS]` and `[SEP]`.
     *
     * Unlike the generating backends, the specials are counted: they occupy positions in this
     * model's context, and a caller checking whether a document fits cares about the number that
     * limit applies to.
     */
    override suspend fun countTokens(text: String): Int =
        WordPiece.encode(text, vocabulary, maxTokens).size

    /** Nothing to retune: an encoder has no sampler. Kept so a registry can set knobs blindly. */
    override suspend fun updateGenerationParameters(parameters: GenerationParameters) {
        guard.whileOpen { generationParameters = parameters }
    }

    override suspend fun updateRuntimeSettings(settings: RuntimeSettings) {
        guard.whileOpen {
            check(settings.accelerator == Accelerator.CPU) {
                "this backend runs ONNX Runtime's CPU provider; ${settings.accelerator} is not wired up"
            }
        }
    }

    internal suspend fun close() = guard.close { model.close() }

    /**
     * Pads to the longest row in the batch.
     *
     * To the longest row, not to [maxTokens]: a batch of short texts should not pay for positions it
     * does not use, and the mask is what tells pooling which ones are real.
     */
    private fun batchOf(encoded: List<List<Int>>): TokenBatch {
        val width = encoded.maxOf { it.size }
        val pad = vocabulary[WordPiece.PAD] ?: 0

        return TokenBatch(
            inputIds = Array(encoded.size) { row ->
                LongArray(width) { column -> (encoded[row].getOrNull(column) ?: pad).toLong() }
            },
            attentionMask = Array(encoded.size) { row ->
                LongArray(width) { column -> if (column < encoded[row].size) 1L else 0L }
            },
            // Single-sequence input, so every token is segment zero. The graph may not even ask.
            tokenTypeIds = Array(encoded.size) { LongArray(width) },
        )
    }
}
