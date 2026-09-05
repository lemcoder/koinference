package io.github.lemcoder.koinference.onnx.internal

/** What loading an ONNX graph needs. */
internal data class OnnxModelOptions(
    val modelPath: String,
    val threads: Int,
)
