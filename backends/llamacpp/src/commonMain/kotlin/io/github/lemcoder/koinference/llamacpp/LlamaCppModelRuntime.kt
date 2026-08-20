package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.ModelRuntime
import io.github.lemcoder.koinference.RuntimeSettings
import io.github.lemcoder.koinference.StreamingTextRuntime
import io.github.lemcoder.koinference.TextRuntime
import io.github.lemcoder.koinference.TokenCounting

/**
 * What a loaded GGUF model can do.
 *
 * Text only. There was an embedding counterpart here with no implementation behind it; it is
 * gone, along with the sealed parent that existed to hold the two apart and the downcast every
 * caller of [LlamaCppModelLoader.load] needed as a result. `koi_embed` is still in the facade —
 * see `docs/backends.md` for why a C function outlives its Kotlin surface.
 */
interface LlamaCppTextRuntime : ModelRuntime, TextRuntime, StreamingTextRuntime, TokenCounting {

    /** What the next session will be created with. */
    val generationParameters: GenerationParameters

    /** Where the model is currently running. */
    val runtimeSettings: RuntimeSettings

    // generateResponse comes from TextRuntime — it is identical across backends. These two are
    // not: llama.cpp fixes its sampler when a session opens, so changing either rebuilds the
    // session, and a backend change additionally reloads the weights. The LiteRT-LM equivalent
    // reopens a conversation instead, and reads a different subset of GenerationParameters.
    //
    // Both suspend. Neither is a field assignment: they free native memory a generation may be
    // using, so they wait for it rather than race it.
    suspend fun updateGenerationParameters(parameters: GenerationParameters)

    suspend fun updateRuntimeSettings(settings: RuntimeSettings)

    /**
     * CPUs the decode threads are pinned to, ascending; empty for the platform's default
     * placement.
     *
     * Here rather than in `:core` because only this backend can answer it. LiteRT-LM manages its
     * own threads and exposes no control over where they run, so an interface in `:core` would
     * describe one implementer — and `:core` holds what every backend does identically. If a
     * second engine ever gains placement control, that is the moment to promote this.
     *
     * Placement is not a detail on a big.LITTLE phone: a decode thread scheduled onto a little
     * core makes every barrier wait for it, and one misplaced worker can halve throughput.
     */
    suspend fun pinnedCpus(): List<Int>

    /**
     * Pin the decode threads to [cpus], or pass an empty list for default placement.
     *
     * Suspends because it rebuilds the thread pool, and doing that under a running generation
     * would pull the workers out from beneath it. CPUs this process may not use are dropped rather
     * than honoured — an app's cpuset is not the SoC's topology.
     */
    suspend fun pinToCpus(cpus: List<Int>)
}
