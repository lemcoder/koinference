package io.github.lemcoder.koinference.llamacpp.internal

import io.github.lemcoder.koinference.runtime.GenerationParameters
import io.github.lemcoder.koinference.runtime.Accelerator
import kotlinx.coroutines.flow.Flow

internal data class SessionOptions(
    /** Context size in tokens; 0 uses the model's trained size. */
    val nCtx: Int = 0,
    /** CPU threads; 0 lets the facade pick. */
    val nThreads: Int = 0,
    /** Maximum tokens to generate; 0 uses the facade's default. */
    val nPredict: Int = 0,
    val temperature: Float = DEFAULT_TEMPERATURE,
    val topK: Int = DEFAULT_TOP_K,
    val minP: Float = DEFAULT_MIN_P,
)
