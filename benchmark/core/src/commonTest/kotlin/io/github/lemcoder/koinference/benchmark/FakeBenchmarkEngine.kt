package io.github.lemcoder.koinference.benchmark

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * An engine that emits canned chunks on a controllable clock.
 *
 * With [FakePlatformProbe] this is what makes the protocol testable without a model: the runner's
 * decisions — warmup kept out of the samples, what a failed record carries, which notes it
 * carries — are decisions about bookkeeping, and none of them need real inference to check.
 */
internal class FakeBenchmarkEngine(
    override val id: String = "fake",
    /** Chunks each generation emits, in order. */
    private val chunks: List<String> = listOf("Hel", "lo ", "there"),
    /** Nanoseconds the probe advances per chunk, so timings are exact rather than flaky. */
    private val nanosPerChunk: Long = 10_000_000L,
    private val probe: FakePlatformProbe,
    /** Throws from [initialize] when set, standing in for a model that cannot be loaded. */
    private val failOnInitialize: Throwable? = null,
    /** Throws part way through the first generation when set. */
    private val failOnGenerate: Throwable? = null,
) : BenchmarkInferenceEngine {

    var appliedWorkload: WorkloadConfig? = null
        private set
    var appliedSampling: SamplingConfig? = null
        private set
    var generations = 0
        private set
    var closed = false
        private set

    override fun metadata(config: BenchmarkModelConfig): Map<String, String> =
        mapOf("fake" to "true")

    override fun applyWorkload(workload: WorkloadConfig, sampling: SamplingConfig) {
        appliedWorkload = workload
        appliedSampling = sampling
    }

    override suspend fun initialize(config: BenchmarkModelConfig): BenchmarkInferenceEngine.EngineSession {
        failOnInitialize?.let { throw it }
        // Model loading costs time, so the clock moves: otherwise modelLoadMs is 0 and a test
        // cannot tell "measured nothing" from "measured zero".
        probe.advance(50_000_000L)
        return Session()
    }

    private inner class Session : BenchmarkInferenceEngine.EngineSession {

        override fun stream(request: GenerationRequest): Flow<String> = flow {
            generations++
            failOnGenerate?.let { throw it }
            chunks.forEach { chunk ->
                probe.advance(nanosPerChunk)
                emit(chunk)
            }
        }

        /** Whitespace words; the point is that the harness counts, not how well this counts. */
        override suspend fun countTokens(text: String): Int? =
            text.split(" ").count { it.isNotBlank() }

        override suspend fun close() {
            closed = true
        }
    }
}

/** An engine with no tokenizer, for the null-count path. */
internal class TokenizerlessEngine(
    override val id: String = "no-tokenizer",
    private val probe: FakePlatformProbe,
) : BenchmarkInferenceEngine {

    override fun metadata(config: BenchmarkModelConfig): Map<String, String> = emptyMap()

    override fun applyWorkload(workload: WorkloadConfig, sampling: SamplingConfig) = Unit

    override suspend fun initialize(config: BenchmarkModelConfig): BenchmarkInferenceEngine.EngineSession =
        object : BenchmarkInferenceEngine.EngineSession {
            override fun stream(request: GenerationRequest): Flow<String> = flow {
                probe.advance(10_000_000L)
                emit("one")
                probe.advance(10_000_000L)
                emit(" two")
            }

            override suspend fun countTokens(text: String): Int? = null

            override suspend fun close() = Unit
        }
}

/**
 * An engine that hands its whole reply over in one chunk.
 *
 * A binding that buffered would look like this, and would satisfy every other property of
 * streaming while making time to first token equal to total latency.
 */
internal class BufferingEngine(
    override val id: String = "buffered",
    private val probe: FakePlatformProbe,
) : BenchmarkInferenceEngine {

    override fun metadata(config: BenchmarkModelConfig): Map<String, String> = emptyMap()

    override fun applyWorkload(workload: WorkloadConfig, sampling: SamplingConfig) = Unit

    override suspend fun initialize(config: BenchmarkModelConfig): BenchmarkInferenceEngine.EngineSession =
        object : BenchmarkInferenceEngine.EngineSession {
            override fun stream(request: GenerationRequest): Flow<String> = flow {
                probe.advance(30_000_000L)
                emit("one two three four")
            }

            override suspend fun countTokens(text: String): Int =
                text.split(" ").count { it.isNotBlank() }

            override suspend fun close() = Unit
        }
}

/**
 * A probe whose clock only moves when a test says so.
 *
 * Every reading is null, which is also what the real host probe reports: a metric the platform
 * cannot produce is absent, never zero.
 */
internal class FakePlatformProbe(
    private var nanos: Long = 1_000_000_000L,
    private val memory: MemorySnapshot? = null,
    private val thermal: ThermalSample? = null,
    private val battery: BatteryReading? = null,
) : PlatformProbe {

    fun advance(by: Long) {
        nanos += by
    }

    override fun monotonicNanos(): Long = nanos

    override fun describeDevice(): DeviceInfo = DeviceInfo()

    override fun readMemory(): MemorySnapshot? = memory

    override fun readThermal(): ThermalSample? = thermal

    override fun readBattery(): BatteryReading? = battery

    override fun processUptimeMs(): Double? = null
}
