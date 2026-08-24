package io.github.lemcoder.koinference.litertlm.internal

import io.github.lemcoder.koinference.runtime.Accelerator
import io.github.lemcoder.koinference.litertlm.jni.kniBridge0
import io.github.lemcoder.koinference.litertlm.jni.kniBridge1
import io.github.lemcoder.koinference.litertlm.jni.kniBridge10
import io.github.lemcoder.koinference.litertlm.jni.kniBridge11
import io.github.lemcoder.koinference.litertlm.jni.kniBridge2
import io.github.lemcoder.koinference.litertlm.jni.kniBridge4
import io.github.lemcoder.koinference.litertlm.jni.kniBridge5
import io.github.lemcoder.koinference.litertlm.jni.kniBridge6
import io.github.lemcoder.koinference.litertlm.jni.kniBridge7
import io.github.lemcoder.koinference.litertlm.jni.kniBridge8
import io.github.lemcoder.koinference.litertlm.jni.kniBridge9
import io.github.lemcoder.koinference.litertlm.jni.kniCString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object JniBridge : LiteRtLmBridge {
    override fun openEngine(options: EngineOptions): LiteRtLmEngine {
        val handle = kniBridge1(
            options.modelPath,
            options.cacheDir,
            when (options.accelerator) {
                // The cinterop leg imports these from the generated bindings; the JNI leg has no
                // such bindings, so BackendId mirrors the header. BackendIdTest fails if they drift.
                Accelerator.GPU -> BackendId.GPU
                Accelerator.CPU -> BackendId.CPU
            },
            options.nThreads,
            options.maxTokens,
        )
        check(handle != 0L) { "LiteRT-LM could not load ${options.modelPath}: ${lastError()}" }
        return JniEngine(handle)
    }
}
