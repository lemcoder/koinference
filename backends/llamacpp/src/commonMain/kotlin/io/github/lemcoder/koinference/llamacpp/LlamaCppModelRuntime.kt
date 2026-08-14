package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.ModelRuntime
import io.github.lemcoder.koinference.RuntimeSettings
import io.github.lemcoder.koinference.TextRuntime

sealed interface LlamaCppModelRuntime : ModelRuntime

interface LlamaCppTextRuntime : LlamaCppModelRuntime, TextRuntime {
    // generateResponse comes from TextRuntime. These two stay here: the signatures happen to
    // match LiteRT-LM's but the contracts do not — changing the backend here reloads the
    // model, because llama.cpp decides GPU offload at load time — and GenerationParameters is
    // shaped around this backend's sampler (topK/minP) rather than being neutral.
    fun updateGenerationParameters(parameters: GenerationParameters)
    fun updateRuntimeSettings(settings: RuntimeSettings)
}

interface LlamaCppEmbeddingRuntime : LlamaCppModelRuntime {
    suspend fun embed(text: String): FloatArray
    fun updateRuntimeSettings(settings: RuntimeSettings)
}
