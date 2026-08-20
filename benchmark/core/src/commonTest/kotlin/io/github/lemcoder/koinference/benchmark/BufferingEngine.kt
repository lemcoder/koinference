package io.github.lemcoder.koinference.benchmark

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
