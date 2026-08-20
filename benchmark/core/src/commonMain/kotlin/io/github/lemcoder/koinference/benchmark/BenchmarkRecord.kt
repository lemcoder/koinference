package io.github.lemcoder.koinference.benchmark

import kotlinx.serialization.Serializable

/**
 * What happened to one (engine, model, workload) combination.
 *
 * [status] is not decoration. A record that failed keeps whatever it managed to collect, so
 * partial data stays readable, but no consumer can mistake it for a measurement: the analysis
 * tool drops anything that is not [BenchmarkStatus.SUCCESS] before it computes a statistic.
 */
@Serializable
data class BenchmarkRecord(
    val engine: EngineInfo,
    val workload: WorkloadInfo,
    val status: BenchmarkStatus,
    val failureReason: String? = null,
    val initialization: InitializationMetrics? = null,
    /**
     * Every measured iteration, in order, with nothing dropped — no averaging, no outlier
     * removal. Summary statistics belong in the analysis layer, where the raw numbers they
     * came from are still visible.
     */
    val samples: List<GenerationSample> = emptyList(),
    /** Warmup iterations, kept separately so they can never be mistaken for measurements. */
    val warmupSamples: List<GenerationSample> = emptyList(),
    val memory: MemoryMetrics? = null,
    val thermal: ThermalMetrics? = null,
    val battery: BatteryMetrics? = null,
    val sustained: SustainedMetrics? = null,
    /** Engine-specific settings, flat, outside the common schema. */
    val engineMetadata: Map<String, String> = emptyMap(),
    /** Anything the harness had to compromise on for this record. */
    val notes: List<String> = emptyList(),
)
