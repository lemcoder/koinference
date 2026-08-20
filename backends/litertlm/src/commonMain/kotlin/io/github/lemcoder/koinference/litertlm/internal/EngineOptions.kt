package io.github.lemcoder.koinference.litertlm.internal

import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.Accelerator
import kotlinx.coroutines.flow.Flow

internal data class EngineOptions(
    val modelPath: String,
    val cacheDir: String? = null,
    val accelerator: Accelerator = Accelerator.CPU,
    /** CPU threads; 0 leaves the engine default. */
    val nThreads: Int = 0,
    /** Engine-wide token budget; 0 uses the model's own. */
    val maxTokens: Int = 0,
)
