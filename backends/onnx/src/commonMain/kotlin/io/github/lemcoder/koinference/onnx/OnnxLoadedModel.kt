package io.github.lemcoder.koinference.onnx

import io.github.lemcoder.koinference.onnx.internal.OnnxModel
import io.github.lemcoder.koinference.onnx.internal.PoolingMode
import io.github.lemcoder.koinference.runtime.Connection
import io.github.lemcoder.koinference.runtime.Model
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A loaded ONNX encoder: the graph, and a factory for embedding connections over it. */
class OnnxLoadedModel internal constructor(
    private val model: OnnxModel,
    private val vocabulary: Map<String, Int>,
    private val poolingMode: PoolingMode,
    private val maxTokens: Int,
    private val modelPath: String,
) : Model {

    private var closed = false

    override suspend fun open(): Connection =
        OnnxEmbeddingConnection(model, vocabulary, poolingMode, maxTokens, modelPath)

    override suspend fun close() {
        if (closed) return
        closed = true
        withContext(Dispatchers.Default) { model.close() }
    }
}
