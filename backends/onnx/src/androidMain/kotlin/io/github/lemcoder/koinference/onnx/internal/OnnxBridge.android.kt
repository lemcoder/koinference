package io.github.lemcoder.koinference.onnx.internal

/** Both legs link the same ONNX Runtime Java API; only the packaged natives differ. */
internal actual fun platformBridge(): OnnxBridge = OrtBridge
