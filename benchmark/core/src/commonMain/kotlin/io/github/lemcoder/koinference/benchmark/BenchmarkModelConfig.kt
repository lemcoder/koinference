package io.github.lemcoder.koinference.benchmark

import kotlinx.serialization.Serializable

@Serializable
data class BenchmarkModelConfig(
    /** Stable name for the weights, shared by every engine's copy of them. */
    val modelId: String,
    val modelVersion: String,
    /** Absolute path on the device or host. */
    val modelPath: String,
    /** e.g. "q8_0", "fp16". The label the file's producer used, not a guess from its size. */
    val quantization: String,
    /** SHA-256 of the model file, filled in by whoever staged it. Null if it was not computed. */
    val sha256: String? = null,
    val maxContextTokens: Int = 0,
    /**
     * Writable directory an engine may cache prepared weights in.
     *
     * Must be writable by *this* process: pointed at a read-only location, LiteRT-LM rebuilds
     * every prefill signature on each load and a 1.2B model is killed for RSS before it answers.
     */
    val cacheDir: String? = null,
    /** CPU threads; 0 leaves the engine's own default. */
    val threads: Int = 0,
    val useGpu: Boolean = false,
)
