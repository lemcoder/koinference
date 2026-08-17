package io.github.lemcoder.koinference.benchmark

import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.InferenceBackend
import io.github.lemcoder.koinference.InstrumentedRuntime
import io.github.lemcoder.koinference.RuntimeSettings
import io.github.lemcoder.koinference.TextRuntime
import io.github.lemcoder.koinference.litertlm.LiteRtLmModelLoader
import io.github.lemcoder.koinference.llamacpp.LlamaCppModelLoader

/**
 * Every engine this build can benchmark.
 *
 * `engine=all` runs these in this order. An id that is not here is a configuration error and
 * fails the record rather than being silently skipped.
 */
fun availableEngines(): List<BenchmarkInferenceEngine> = listOf(
    LlamaCppBenchmarkEngine(),
    LiteRtLmBenchmarkEngine(),
)

fun engineById(id: String): BenchmarkInferenceEngine? = availableEngines().firstOrNull { it.id == id }

/**
 * Shared by both adapters: the only thing they do differently is construct a loader.
 *
 * Sampling is applied through the common [GenerationParameters] so that both engines get the
 * same request. Each backend documents which knobs it ignores — llama.cpp has no seed, and
 * LiteRT-LM has no min-p — and neither substitutes one for another, so what a run actually
 * applied is recoverable from [BenchmarkInferenceEngine.metadata] plus the backend's docs.
 */
private abstract class TextRuntimeEngine : BenchmarkInferenceEngine {

    protected abstract suspend fun loadRuntime(config: BenchmarkModelConfig): Pair<TextRuntime, suspend () -> Unit>

    protected fun parameters(config: BenchmarkModelConfig, sampling: SamplingConfig) =
        GenerationParameters(
            topK = sampling.topK,
            topP = sampling.topP,
            temperature = sampling.temperature,
            seed = sampling.seed,
        )

    override suspend fun initialize(config: BenchmarkModelConfig): BenchmarkInferenceEngine.EngineSession {
        val (runtime, release) = loadRuntime(config)
        return RuntimeSession(runtime, release)
    }

    private class RuntimeSession(
        private val runtime: TextRuntime,
        private val release: suspend () -> Unit,
    ) : BenchmarkInferenceEngine.EngineSession {

        private val probe = platformProbe()

        override suspend fun generate(request: GenerationRequest): GenerationResult {
            val start = probe.monotonicNanos()
            val text = runtime.generateResponse(request.prompt)
            val wallClockMs = (probe.monotonicNanos() - start) / 1_000_000.0

            return GenerationResult(
                text = text,
                wallClockMs = wallClockMs,
                telemetry = (runtime as? InstrumentedRuntime)?.lastGeneration,
            )
        }

        override suspend fun close() = release()
    }
}

/**
 * llama.cpp.
 *
 * Its max-new-tokens is a property of the session (`n_predict`), not of a request, so the
 * runtime is loaded per workload by the runner rather than shared across workloads with
 * different limits.
 */
private class LlamaCppBenchmarkEngine : TextRuntimeEngine() {

    override val id: String = "llama.cpp"

    var maxNewTokens: Int = 0
    var sampling: SamplingConfig = SamplingConfig()

    override fun metadata(config: BenchmarkModelConfig): Map<String, String> = buildMap {
        put("backend", if (config.useGpu) "GPU" else "CPU")
        put("gpuOffload", config.useGpu.toString())
        put("threads", config.threads.toString())
        put("contextTokens", config.maxContextTokens.toString())
        put("maxNewTokens", maxNewTokens.toString())
        // Sampling as actually applied: llama.cpp's facade takes no seed, so recording the
        // configured one here would claim something the engine never saw.
        put("temperature", sampling.temperature.toString())
        sampling.topK?.let { put("topK", it.toString()) }
        put("seedApplied", "false")
        put("topPApplied", "false")
    }

    override suspend fun loadRuntime(config: BenchmarkModelConfig): Pair<TextRuntime, suspend () -> Unit> {
        val loader = LlamaCppModelLoader(
            settings = RuntimeSettings(
                backend = if (config.useGpu) InferenceBackend.GPU else InferenceBackend.CPU,
            ),
            nCtx = config.maxContextTokens,
            nThreads = config.threads,
            nPredict = maxNewTokens,
        )
        val runtime = loader.load(config.modelPath) as TextRuntime
        (runtime as? io.github.lemcoder.koinference.llamacpp.LlamaCppTextRuntime)
            ?.updateGenerationParameters(parameters(config, sampling))
        return runtime to { loader.unload(config.modelPath) }
    }
}

/** LiteRT-LM. Max output tokens is a conversation setting, so the same per-workload rule applies. */
private class LiteRtLmBenchmarkEngine : TextRuntimeEngine() {

    override val id: String = "litert-lm"

    var maxNewTokens: Int = 0
    var sampling: SamplingConfig = SamplingConfig()

    override fun metadata(config: BenchmarkModelConfig): Map<String, String> = buildMap {
        put("backend", if (config.useGpu) "GPU" else "CPU")
        put("threads", config.threads.toString())
        put("maxTokens", config.maxContextTokens.toString())
        put("maxNewTokens", maxNewTokens.toString())
        put("temperature", sampling.temperature.toString())
        sampling.topK?.let { put("topK", it.toString()) }
        sampling.topP?.let { put("topP", it.toString()) }
        put("seedApplied", "true")
        put("minPApplied", "false")
    }

    override suspend fun loadRuntime(config: BenchmarkModelConfig): Pair<TextRuntime, suspend () -> Unit> {
        val loader = LiteRtLmModelLoader(
            settings = RuntimeSettings(
                backend = if (config.useGpu) InferenceBackend.GPU else InferenceBackend.CPU,
            ),
            parameters = parameters(config, sampling),
            nThreads = config.threads,
            maxTokens = config.maxContextTokens,
        )
        val runtime = loader.load(config.modelPath)
        return runtime to { loader.unload(config.modelPath) }
    }
}

/**
 * The per-workload knobs both adapters need before [BenchmarkInferenceEngine.initialize].
 *
 * Set by the runner, because both engines fix their output limit and sampler when the model is
 * loaded rather than per request — which is also why the runner reloads between workloads
 * instead of reusing one session.
 */
internal fun BenchmarkInferenceEngine.applyWorkload(workload: WorkloadConfig, sampling: SamplingConfig) {
    when (this) {
        is LlamaCppBenchmarkEngine -> {
            maxNewTokens = workload.maxNewTokens
            this.sampling = sampling
        }

        is LiteRtLmBenchmarkEngine -> {
            maxNewTokens = workload.maxNewTokens
            this.sampling = sampling
        }
    }
}
