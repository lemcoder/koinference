package io.github.lemcoder.koinference.benchmark

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

@Serializable
enum class BenchmarkStatus { SUCCESS, FAILED, SKIPPED }

@Serializable
data class EngineInfo(
    val id: String,
    val version: String? = null,
    val modelId: String,
    val modelVersion: String,
    val quantization: String,
    val modelSha256: String? = null,
)

@Serializable
data class WorkloadInfo(
    val promptId: String,
    val promptSha256: String? = null,
    /** Characters, not tokens: the harness has no tokenizer of its own and will not guess. */
    val promptChars: Int,
    val maxNewTokens: Int,
    /** As reported by the engine, when it reports one. */
    val inputTokens: Int? = null,
)

@Serializable
data class InitializationMetrics(
    /** Process start to the harness taking its first measurement. Null off Android. */
    val processStartMs: Double? = null,
    val engineInitMs: Double? = null,
    val modelLoadMs: Double? = null,
    /** Only when the engine initialises its tokenizer separately and says so. */
    val tokenizerInitMs: Double? = null,
)

/**
 * One iteration.
 *
 * [wallClockMs] is always present; everything under [telemetrySource] is only as good as the
 * source says it is. Records taken through different sources are never averaged together —
 * see [io.github.lemcoder.koinference.TelemetrySource].
 */
@Serializable
data class GenerationSample(
    val iteration: Int,
    val wallClockMs: Double,
    val telemetrySource: String? = null,
    val ttftMs: Double? = null,
    val prefillMs: Double? = null,
    val decodeMs: Double? = null,
    val promptTokens: Int? = null,
    val generatedTokens: Int? = null,
    val prefillTokensPerSecond: Double? = null,
    val decodeTokensPerSecond: Double? = null,
    /**
     * Generated tokens over the whole call, prefill included. Null when the engine reports no
     * token count — it is never derived from the character count of the reply.
     */
    val endToEndTokensPerSecond: Double? = null,
    /** Characters produced, which is not a token count and is not used as one. */
    val outputChars: Int,
    val peakPssKb: Long? = null,
)

/**
 * Process memory around the run.
 *
 * Java heap alone would be close to meaningless here: llama.cpp holds the weights in native
 * memory, so a comparison against LiteRT-LM has to be made on PSS or RSS.
 */
@Serializable
data class MemoryMetrics(
    val beforeInitPssKb: Long? = null,
    val afterLoadPssKb: Long? = null,
    val afterWarmupPssKb: Long? = null,
    val peakPssKb: Long? = null,
    val afterRunPssKb: Long? = null,
    val nativeHeapKb: Long? = null,
    val javaHeapKb: Long? = null,
    val rssKb: Long? = null,
)

@Serializable
data class ThermalMetrics(
    val batteryTemperatureBeforeC: Double? = null,
    val batteryTemperatureAfterC: Double? = null,
    val batteryTemperaturePeakC: Double? = null,
    /** PowerManager.getCurrentThermalStatus(), as its constant name. Null before API 29. */
    val thermalStatusBefore: String? = null,
    val thermalStatusAfter: String? = null,
    val thermalStatusPeak: String? = null,
    /** Samples taken during the run, oldest first. Empty when nothing could be read. */
    val samples: List<ThermalSample> = emptyList(),
)

@Serializable
data class ThermalSample(
    val atMs: Double,
    val batteryTemperatureC: Double? = null,
    val thermalStatus: String? = null,
    /** Per-core scaling frequency in kHz, when /sys is readable without root. */
    val cpuFrequenciesKhz: List<Long> = emptyList(),
)

/**
 * Battery, deliberately coarse.
 *
 * A percentage delta is not energy. On most devices the level moves in whole percent, so a
 * short run reads zero regardless of what it drew, and Firebase Test Lab devices are mains
 * powered besides. These fields exist to be honest about that, and the shape leaves room for
 * a real energy counter later.
 */
@Serializable
data class BatteryMetrics(
    val percentBefore: Int? = null,
    val percentAfter: Int? = null,
    val percentDelta: Int? = null,
    val charging: Boolean? = null,
    /** BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER, nanowatt-hours, when the device has it. */
    val energyCounterNwhBefore: Long? = null,
    val energyCounterNwhAfter: Long? = null,
)

@Serializable
data class SustainedMetrics(
    val requestedSeconds: Int,
    val actualSeconds: Double,
    val iterations: Int,
    /** Throughput of each iteration in order, so a decline over time is visible. */
    val decodeTokensPerSecondSeries: List<Double> = emptyList(),
    val wallClockMsSeries: List<Double> = emptyList(),
    val thermalSamples: List<ThermalSample> = emptyList(),
)

/**
 * The device a run happened on.
 *
 * The marketing model name is not enough to identify hardware — two "Galaxy S23" phones can
 * carry different SoCs — so the SoC, ABI and core layout are recorded where the platform
 * exposes them.
 */
@Serializable
data class DeviceInfo(
    val manufacturer: String? = null,
    val model: String? = null,
    val device: String? = null,
    val androidVersion: String? = null,
    val sdk: Int? = null,
    val abi: String? = null,
    val supportedAbis: List<String> = emptyList(),
    val socManufacturer: String? = null,
    val socModel: String? = null,
    val hardware: String? = null,
    val cpuCores: Int? = null,
    val cpuMaxFrequenciesKhz: List<Long> = emptyList(),
    val ramMb: Long? = null,
    /** Set from the FTL matrix when the harness is told which entry it is running as. */
    val ftlModelId: String? = null,
    val ftlVersion: String? = null,
    /** Set when the harness knows it is not on real hardware. */
    val isEmulator: Boolean? = null,
    /** Host platform when this is not an Android run at all, e.g. "macosArm64". */
    val hostPlatform: String? = null,
)
