package io.github.lemcoder.koinference.benchmark.engine

import io.github.lemcoder.koinference.runtime.Accelerator
import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.backend.BackendRegistry
import io.github.lemcoder.koinference.runtime.GenerationParameters
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import io.github.lemcoder.koinference.backend.SamplingKnob
import io.github.lemcoder.koinference.runtime.StreamingTextRuntime
import io.github.lemcoder.koinference.runtime.TokenCounting
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
