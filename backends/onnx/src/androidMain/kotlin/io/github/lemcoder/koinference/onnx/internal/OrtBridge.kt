package io.github.lemcoder.koinference.onnx.internal

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer

/**
 * ONNX Runtime through its Java API.
 *
 * This file is duplicated verbatim in the other leg. The two artifacts — `onnxruntime-android` and
 * `onnxruntime` — expose the same `ai.onnxruntime` API and differ only in which natives they carry,
 * so there is nothing here to abstract over; see the rules at the top of `CLAUDE.md`.
 */
internal object OrtBridge : OnnxBridge {

    // One environment per process, as ONNX Runtime intends: it owns the thread pools and logging,
    // and a second one is a second set of both.
    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    override fun openModel(options: OnnxModelOptions): OnnxModel {
        val sessionOptions = OrtSession.SessionOptions().apply {
            if (options.threads > 0) {
                setIntraOpNumThreads(options.threads)
                // One graph at a time here, so inter-op parallelism buys nothing and costs threads.
                setInterOpNumThreads(1)
            }
        }
        return OrtModel(environment, environment.createSession(options.modelPath, sessionOptions))
    }
}
