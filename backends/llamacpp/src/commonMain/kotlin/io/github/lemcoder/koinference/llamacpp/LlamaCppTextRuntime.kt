package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.runtime.GenerationParameters
import io.github.lemcoder.koinference.runtime.ModelRuntime
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import io.github.lemcoder.koinference.runtime.StreamingTextRuntime
import io.github.lemcoder.koinference.runtime.TextRuntime
import io.github.lemcoder.koinference.runtime.TokenCounting

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

}
