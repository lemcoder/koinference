package io.github.lemcoder.koinference.benchmark.engine

import io.github.lemcoder.koinference.runtime.Accelerator
import io.github.lemcoder.koinference.backend.Backend
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
val benchmarkBackends: List<Backend> = listOf(LlamaCpp, LiteRtLm)

/**
 * A list rather than a [io.github.lemcoder.koinference.Koinference], because the harness adapts each
 * backend separately: it loads one engine per process and needs each one's `honours` and `id` to
 * fill in a record. Koinference is for a caller that wants a model loaded and does not care which
 * engine answers, which is the opposite of what a benchmark wants.
 */
fun availableEngines(): List<BenchmarkInferenceEngine> = benchmarkBackends.map(::BackendEngine)
