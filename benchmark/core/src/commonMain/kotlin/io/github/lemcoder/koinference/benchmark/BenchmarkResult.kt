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
)

@Serializable
data class InitializationMetrics(
    /** Process start to the harness taking its first measurement. Null off Android. */
    val processStartMs: Double? = null,
    val modelLoadMs: Double? = null,
    /**
     * Neither engine separates tokenizer setup from model loading, and neither is asked to
     * report it: it stays null rather than being carved out of modelLoadMs by guesswork.
     */
    val tokenizerInitMs: Double? = null,
)

/**
 * One iteration.
 *
 * Everything here was measured by the harness, above the engine, with one clock — see
 * [measureGeneration]. No field comes from an engine describing itself.
 */
@Serializable
data class GenerationSample(
    val iteration: Int,
    /** Ask to last chunk, measured by the harness. */
    val wallClockMs: Double,
    /**
     * Ask to *first* chunk. Null only when the engine produced nothing at all.
     *
     * Measured above the engine, identically for every engine, so this number means the same
     * thing in every row. It includes the binding the chunk travelled through, which is what a
     * caller actually waits for.
     */
    val ttftMs: Double? = null,
    /** First chunk to last, so throughput does not vary with prompt length. */
    val streamingMs: Double? = null,
    /**
     * Emissions, not tokens — one token per chunk for llama.cpp, whatever LiteRT-LM sends for
     * LiteRT-LM. Named for what it is so nobody divides it into a token throughput.
     */
    val chunks: Int = 0,
    val chunksPerSecond: Double? = null,
    /** Characters produced. Not a token count, and never used as one. */
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
    /** Chunks/sec of each iteration in order, so a decline under load is visible. */
    val chunksPerSecondSeries: List<Double> = emptyList(),
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
