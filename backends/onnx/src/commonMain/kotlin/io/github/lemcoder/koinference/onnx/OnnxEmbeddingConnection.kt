package io.github.lemcoder.koinference.onnx

import io.github.lemcoder.koinference.onnx.internal.OnnxModel
import io.github.lemcoder.koinference.onnx.internal.Pooling
import io.github.lemcoder.koinference.onnx.internal.PoolingMode
import io.github.lemcoder.koinference.onnx.internal.TokenBatch
import io.github.lemcoder.koinference.onnx.internal.WordPiece
import io.github.lemcoder.koinference.runtime.ConnectionGuard
import io.github.lemcoder.koinference.runtime.EmbeddingConnection
import io.github.lemcoder.koinference.runtime.text.TokenCounting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An embedding connection over a loaded ONNX encoder.
 *
 * Embedding is stateless — no KV cache — so [close] releases nothing; the model belongs to
 * [OnnxLoadedModel]. Several connections share the model freely.
 */
class OnnxEmbeddingConnection internal constructor(
    private val model: OnnxModel,
    private val vocabulary: Map<String, Int>,
    private val poolingMode: PoolingMode,
    private val maxTokens: Int,
    private val target: String,
) : EmbeddingConnection, TokenCounting {

    override val dimensions: Int get() = model.hiddenSize

    private val guard = ConnectionGuard { target }

    override val isClosed: Boolean get() = guard.isClosed

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
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

    override suspend fun countTokens(text: String): Int =
        WordPiece.encode(text, vocabulary, maxTokens).size

    override suspend fun close() = guard.close { }

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
            tokenTypeIds = Array(encoded.size) { LongArray(width) },
        )
    }
}
