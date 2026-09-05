package io.github.lemcoder.koinference.onnx.internal

import kotlin.math.sqrt

/**
 * Turns per-token hidden states into one vector per text.
 *
 * Kotlin, and tested without a graph: this is the step where an embedding silently becomes wrong.
 * Pooling the padding into the mean, or skipping normalisation, produces vectors that look fine and
 * rank badly, which no exception will tell you about.
 *
 * **Which pooling is a property of the model, not a preference.** BGE embeds into the `[CLS]`
 * position and sentence-transformers models generally mean-pool; using the wrong one costs retrieval
 * quality quietly. `sentence-transformers` records the answer in `1_Pooling/config.json`, which
 * [PoolingMode.from] reads.
 */
internal object Pooling {

    /**
     * @param hidden `[token][hidden]` for one text.
     * @param mask 1 for a real token, 0 for padding.
     */
    fun pool(hidden: Array<FloatArray>, mask: LongArray, mode: PoolingMode): FloatArray = when (mode) {
        PoolingMode.CLS -> hidden.first().copyOf()
        PoolingMode.MEAN -> mean(hidden, mask)
    }

    /**
     * Mean over real tokens only.
     *
     * The mask is why this cannot be a plain average: padding rows are still numbers, and including
     * them pulls every vector toward whatever the model emits for `[PAD]`, by an amount that depends
     * on how long the *other* texts in the batch were.
     */
    private fun mean(hidden: Array<FloatArray>, mask: LongArray): FloatArray {
        val sum = FloatArray(hidden.first().size)
        var counted = 0
        hidden.forEachIndexed { token, values ->
            if (mask.getOrElse(token) { 0L } == 1L) {
                counted++
                values.forEachIndexed { index, value -> sum[index] += value }
            }
        }
        if (counted == 0) return sum
        return FloatArray(sum.size) { sum[it] / counted }
    }

    /**
     * Scales to unit length, so a dot product is a cosine similarity.
     *
     * Every consumer of these vectors — a vector database, a RAG retriever — assumes it, and the
     * OpenAI embeddings this backend is meant to be compared against are normalised too.
     */
    fun normalise(vector: FloatArray): FloatArray {
        var sum = 0.0
        vector.forEach { sum += it.toDouble() * it }
        val length = sqrt(sum).toFloat()
        if (length == 0f) return vector
        return FloatArray(vector.size) { vector[it] / length }
    }
}
