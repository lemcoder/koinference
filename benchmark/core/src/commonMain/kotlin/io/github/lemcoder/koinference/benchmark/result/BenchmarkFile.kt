package io.github.lemcoder.koinference.benchmark.result

import io.github.lemcoder.koinference.benchmark.config.BENCHMARK_VERSION
import kotlinx.serialization.Serializable

/**
 * One results file: the run, and every record it produced.
 *
 * A file rather than a record per file so that a single FTL artifact carries the whole run,
 * including the records that failed.
 */
@Serializable
data class BenchmarkFile(
    val benchmarkVersion: String = BENCHMARK_VERSION,
    val runId: String,
    val device: DeviceInfo,
    val records: List<BenchmarkRecord>,
)
