package io.github.lemcoder.koinference.benchmark

import io.github.lemcoder.koinference.benchmark.config.BenchmarkModelConfig
import io.github.lemcoder.koinference.benchmark.config.SamplingConfig
import io.github.lemcoder.koinference.benchmark.config.WorkloadConfig
import io.github.lemcoder.koinference.benchmark.engine.BenchmarkInferenceEngine
import io.github.lemcoder.koinference.benchmark.engine.GenerationRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
