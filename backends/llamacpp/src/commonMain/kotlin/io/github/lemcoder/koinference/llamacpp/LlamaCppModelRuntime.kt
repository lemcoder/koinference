package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.ModelRuntime
import io.github.lemcoder.koinference.RuntimeSettings
import io.github.lemcoder.koinference.StreamingTextRuntime
import io.github.lemcoder.koinference.TextRuntime

sealed interface LlamaCppModelRuntime : ModelRuntime

interface LlamaCppTextRuntime : LlamaCppModelRuntime, TextRuntime, StreamingTextRuntime {
    /** What the next session will be created with. */
    val generationParameters: GenerationParameters

    /** Where the model is currently running. */
    val runtimeSettings: RuntimeSettings

    // generateResponse comes from TextRuntime. These two stay here: the signatures happen to
    // match LiteRT-LM's but the contracts do not — changing the backend here reloads the
    // model, because llama.cpp decides GPU offload at load time — and llama.cpp reads a
    // different subset of GenerationParameters (topK/minP/temperature) than LiteRT-LM does.
    //
    // Both suspend: they free the session, and freeing it while a generation is decoding into
    // it is a use-after-free, so they wait for the generation rather than race it.
    suspend fun updateGenerationParameters(parameters: GenerationParameters)

    suspend fun updateRuntimeSettings(settings: RuntimeSettings)
}

interface LlamaCppEmbeddingRuntime : LlamaCppModelRuntime {
    suspend fun embed(text: String): FloatArray
    suspend fun updateRuntimeSettings(settings: RuntimeSettings)
}
