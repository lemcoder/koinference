package io.github.lemcoder.koinference.onnx.internal

/** The ONNX Runtime binding, as something a test can replace. */
internal interface OnnxBridge {

    fun openModel(options: OnnxModelOptions): OnnxModel
}

/** The binding this platform links; both legs use ONNX Runtime's own Java API. */
internal expect fun platformBridge(): OnnxBridge
