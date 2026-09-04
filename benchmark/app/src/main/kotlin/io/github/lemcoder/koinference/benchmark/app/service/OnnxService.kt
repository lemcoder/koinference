package io.github.lemcoder.koinference.benchmark.app.service

import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.onnx.Onnx

/** ONNX Runtime, in the `:onnx` process. The embedding engine. See the manifest. */
class OnnxService : BackendService() {
    override val backend: Backend = Onnx
}
