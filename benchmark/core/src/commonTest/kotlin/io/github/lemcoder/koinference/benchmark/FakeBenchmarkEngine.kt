package io.github.lemcoder.koinference.benchmark

import io.github.lemcoder.koinference.benchmark.config.BenchmarkModelConfig
import io.github.lemcoder.koinference.benchmark.config.SamplingConfig
import io.github.lemcoder.koinference.benchmark.config.WorkloadConfig
import io.github.lemcoder.koinference.benchmark.engine.BenchmarkInferenceEngine
import io.github.lemcoder.koinference.benchmark.engine.GenerationRequest
import io.github.lemcoder.koinference.runtime.ResponsePart
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

        override fun stream(request: GenerationRequest): Flow<ResponsePart> = flow {
            generations++
            failOnGenerate?.let { throw it }
            chunks.forEach { chunk ->
                probe.advance(nanosPerChunk)
                emit(ResponsePart.Text(chunk))
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
