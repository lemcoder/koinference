package io.github.lemcoder.koinference.benchmark

import io.github.lemcoder.koinference.Accelerator
import io.github.lemcoder.koinference.Backend
import io.github.lemcoder.koinference.BackendRegistry
import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.ModelConfig
import io.github.lemcoder.koinference.RuntimeSettings
import io.github.lemcoder.koinference.SamplingKnob
import io.github.lemcoder.koinference.StreamingTextRuntime
import io.github.lemcoder.koinference.TokenCounting
import io.github.lemcoder.koinference.litertlm.LiteRtLm
import io.github.lemcoder.koinference.llamacpp.LlamaCpp

/**
 * The backends this build links, in the order `engine=all` runs them.
 *
 * The only file in the harness that names a backend. Adding one to a benchmark is adding it here.
 */
val benchmarkBackends: BackendRegistry = BackendRegistry(LlamaCpp, LiteRtLm)
fun availableEngines(): List<BenchmarkInferenceEngine> =
    benchmarkBackends.backends.map(::BackendEngine)
