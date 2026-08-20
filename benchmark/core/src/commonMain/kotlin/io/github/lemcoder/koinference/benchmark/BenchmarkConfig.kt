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
/** Bumped when the schema changes shape. The analysis tool refuses versions it does not know. */
const val BENCHMARK_VERSION: String = "1"
