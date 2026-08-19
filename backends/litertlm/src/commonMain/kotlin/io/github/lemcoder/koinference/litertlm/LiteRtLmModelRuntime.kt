package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.ModelRuntime
import io.github.lemcoder.koinference.RuntimeSettings
import io.github.lemcoder.koinference.StreamingTextRuntime
import io.github.lemcoder.koinference.TextRuntime

sealed interface LiteRtLmModelRuntime : ModelRuntime

interface LiteRtLmTextRuntime : LiteRtLmModelRuntime, TextRuntime, StreamingTextRuntime {
    /** What the next conversation will be opened with. */
    val generationParameters: GenerationParameters

    /** Where the model is currently running. */
    val runtimeSettings: RuntimeSettings

    // generateResponse comes from TextRuntime — it is identical across backends. These two are
    // not: LiteRT-LM fixes its sampler when a conversation opens, so changing either reopens
    // the conversation and loses its prefilled state. The llama.cpp equivalent rebuilds a
    // session instead, and a backend change there costs a full model reload.
    //
    // Both suspend. Neither is a field assignment: they free native memory that a generation
    // may be using, so they have to wait for it rather than race it, and a backend change
    // reloads the weights.
    suspend fun updateGenerationParameters(parameters: GenerationParameters)

    suspend fun updateRuntimeSettings(settings: RuntimeSettings)

    /**
     * Forget the conversation so far and start the next turn from the system prompt.
     *
     * The weights stay loaded, so this is the cheap way to begin a new chat — and the only
     * way, short of changing a parameter for the side effect of dropping the history.
     */
    suspend fun resetConversation()
}

// No embedding counterpart to :backends:llamacpp's LlamaCppEmbeddingRuntime: LiteRT-LM's C
// API exposes tokenize, detokenize and scoring, but nothing that returns an embedding
// vector. An embedding backend would go through LiteRT core instead.
