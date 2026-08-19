package io.github.lemcoder.koinference.benchmark

import kotlinx.serialization.Serializable

/**
 * Everything that decides what a run measures.
 *
 * Serialized into every result file. Two results are comparable only if a reader can see that
 * these matched, which is why the model identity carries a checksum and a quantization label
 * rather than just a filename: a 4-bit GGUF and an 8-bit .litertlm of the same base weights
 * are not the same experiment, and nothing else in the file would reveal it.
 */
@Serializable
data class BenchmarkConfig(
    val benchmarkRunId: String,
    val benchmarkVersion: String = BENCHMARK_VERSION,
    val engineIds: List<String>,
    val model: BenchmarkModelConfig,
    val workloads: List<WorkloadConfig>,
    val sampling: SamplingConfig = SamplingConfig(),
    /** Iterations discarded before measurement begins. Never mixed into the samples. */
    val warmupIterations: Int = 1,
    val measurementIterations: Int = 5,
    /** 0 disables the sustained phase. */
    val sustainedDurationSeconds: Int = 0,
    val build: BuildInfo = BuildInfo(),
    /**
     * Which Firebase Test Lab matrix entry this process was launched as.
     *
     * Passed in rather than detected: nothing on the device reports its FTL identity, and
     * Build.MODEL on a physical FTL device is the manufacturer's name for it, not the model id
     * the matrix was written with.
     */
    val ftlModelId: String? = null,
    val ftlVersion: String? = null,
)

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

@Serializable
data class WorkloadConfig(
    val promptId: String,
    val maxNewTokens: Int,
)

/**
 * Sampling knobs, applied identically to every engine.
 *
 * Defaults to greedy decoding: llama.cpp's facade exposes no seed, so temperature 0 is the
 * only setting that makes both engines reproducible in the same way. [seed] is still recorded
 * and passed to engines that have one.
 */
@Serializable
data class SamplingConfig(
    val temperature: Double = 0.0,
    val topK: Int? = null,
    val topP: Double? = null,
    val seed: Int = 42,
)

/** Identifies the code that produced a result, so a number can be traced back to a build. */
@Serializable
data class BuildInfo(
    val appVersion: String? = null,
    val gitCommit: String? = null,
)

/** Bumped when the schema changes shape. The analysis tool refuses versions it does not know. */
const val BENCHMARK_VERSION: String = "1"
