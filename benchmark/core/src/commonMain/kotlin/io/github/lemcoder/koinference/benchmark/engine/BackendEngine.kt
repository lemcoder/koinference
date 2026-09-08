package io.github.lemcoder.koinference.benchmark.engine

import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.Koinference
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.SamplingKnob
import io.github.lemcoder.koinference.benchmark.config.BenchmarkModelConfig
import io.github.lemcoder.koinference.benchmark.config.SamplingConfig
import io.github.lemcoder.koinference.benchmark.config.WorkloadConfig
import io.github.lemcoder.koinference.litertlm.LiteRtLm
import io.github.lemcoder.koinference.llamacpp.LlamaCpp
import io.github.lemcoder.koinference.runtime.generation.Accelerator
import io.github.lemcoder.koinference.runtime.GeneratingConnection
import io.github.lemcoder.koinference.runtime.generation.GenerationParameters
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import io.github.lemcoder.koinference.runtime.text.TokenCounting
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.channelFlow

/**
 * Adapts any [Backend] to the harness.
 *
 * One class, not one per engine. The two it replaced differed in three things — an id, which
 * loader they constructed, and which sampling knobs they reported as applied — and all three are
 * now things a [Backend] states about itself, so a third engine needs no adapter at all.
 */
internal class BackendEngine(private val backend: Backend) : BenchmarkInferenceEngine {

    override val id: String get() = backend.id

    private var maxNewTokens: Int = 0
    private var sampling: SamplingConfig = SamplingConfig()

    override fun applyWorkload(workload: WorkloadConfig, sampling: SamplingConfig) {
        maxNewTokens = workload.maxNewTokens
        this.sampling = sampling
    }

    /**
     * Sampling as *applied*, not as requested.
     *
     * Read from [Backend.honours] rather than hardcoded per engine: recording a seed the engine
     * never saw would claim a reproducibility this run does not have.
     */
    override fun metadata(config: BenchmarkModelConfig): Map<String, String> = buildMap {
        put("accelerator", if (config.useGpu) "GPU" else "CPU")
        put("threads", config.threads.toString())
        put("contextTokens", config.maxContextTokens.toString())
        put("maxNewTokens", maxNewTokens.toString())
        put("temperature", sampling.temperature.toString())
        sampling.topK?.let { put("topK", it.toString()) }
        sampling.topP?.let { put("topP", it.toString()) }
        SamplingKnob.entries.forEach { knob ->
            put("${knob.name.lowercase()}Applied", (knob in backend.honours).toString())
        }
    }

    override suspend fun initialize(config: BenchmarkModelConfig): BenchmarkInferenceEngine.EngineSession {
        val loader = backend.loader(
            ModelConfig(
                settings = RuntimeSettings(
                    accelerator = if (config.useGpu) Accelerator.GPU else Accelerator.CPU,
                ),
                parameters = GenerationParameters(
                    topK = sampling.topK,
                    topP = sampling.topP,
                    temperature = sampling.temperature,
                    seed = sampling.seed,
                ),
                contextTokens = config.maxContextTokens,
                // Both engines fix this at load time, so without it they are asked for different
                // amounts of work and the comparison means nothing.
                maxOutputTokens = maxNewTokens,
                threads = config.threads,
                cacheDir = config.cacheDir,
            ),
        )
        val model = loader.load(config.modelPath)
        val connection = model.open() as GeneratingConnection
        return ConnectionSession(connection) { loader.unload(config.modelPath) }
    }

    private class ConnectionSession(
        private val connection: GeneratingConnection,
        private val release: suspend () -> Unit,
    ) : BenchmarkInferenceEngine.EngineSession {

        // Bridges the connection's callback to the harness's internal Flow with channelFlow — the
        // consumer-side wrap the public no-Flow rule expects. Nothing else is done to the parts, so
        // the first-chunk measurement is the engine's and not this adapter's.
        override fun stream(request: GenerationRequest) = channelFlow {
            connection.generate(request.prompt) { part -> trySendBlocking(part) }
        }

        override suspend fun countTokens(text: String): Int? =
            (connection as? TokenCounting)?.countTokens(text)

        override suspend fun close() {
            connection.close()
            release()
        }
    }
}
