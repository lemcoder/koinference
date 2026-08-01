package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.GenerationConstraint
import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.ModelRuntime
import io.github.lemcoder.koinference.RuntimeSettings

sealed interface LlamaCppModelRuntime : ModelRuntime

interface LlamaCppTextRuntime : LlamaCppModelRuntime {
    suspend fun generateResponse(
        prompt: String,
        constraint: GenerationConstraint? = null,
    ): String

    fun updateGenerationParameters(parameters: GenerationParameters)
    fun updateRuntimeSettings(settings: RuntimeSettings)
}

interface LlamaCppEmbeddingRuntime : LlamaCppModelRuntime {
    suspend fun embed(text: String): FloatArray
    fun updateRuntimeSettings(settings: RuntimeSettings)
}
