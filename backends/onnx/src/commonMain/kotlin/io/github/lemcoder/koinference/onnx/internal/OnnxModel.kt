package io.github.lemcoder.koinference.onnx.internal

/** A loaded ONNX graph. */
internal interface OnnxModel {

    /** Hidden size, read from the graph's output shape rather than configured. */
    val hiddenSize: Int

    /** Which inputs the graph declares: some encoders take no `token_type_ids`. */
    val inputNames: Set<String>

    /**
     * Runs one batch.
     *
     * Returns last-hidden-state as `[batch][token][hidden]`. Pooling is the caller's, because it is
     * arithmetic and belongs in Kotlin where it can be tested — see [Pooling].
     */
    fun run(batch: TokenBatch): Array<Array<FloatArray>>

    fun close()
}
