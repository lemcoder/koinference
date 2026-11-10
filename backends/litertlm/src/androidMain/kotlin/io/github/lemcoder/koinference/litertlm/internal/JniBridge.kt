package io.github.lemcoder.koinference.litertlm.internal

import io.github.lemcoder.koinference.runtime.generation.Accelerator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import koinference_litertlm.KOILM_BACKEND_CPU
import koinference_litertlm.KOILM_BACKEND_GPU
import koinference_litertlm.koilm_model_load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

internal object JniBridge : LiteRtLmBridge {
    override fun openEngine(options: EngineOptions): LiteRtLmEngine {
        val handle = koilm_model_load(
            options.modelPath,
            options.cacheDir,
            when (options.accelerator) {
                Accelerator.GPU -> KOILM_BACKEND_GPU
                Accelerator.CPU -> KOILM_BACKEND_CPU
            },
            options.nThreads,
            options.maxTokens,
        )
        checkNotNull(handle) { "LiteRT-LM could not load ${options.modelPath}: ${lastError()}" }
        return JniEngine(handle)
    }
}
