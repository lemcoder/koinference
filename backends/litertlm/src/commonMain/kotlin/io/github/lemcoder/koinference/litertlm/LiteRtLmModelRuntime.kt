package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.ModelRuntime
import io.github.lemcoder.koinference.RuntimeSettings
import io.github.lemcoder.koinference.TextRuntime

sealed interface LiteRtLmModelRuntime : ModelRuntime

interface LiteRtLmTextRuntime : LiteRtLmModelRuntime, TextRuntime {
    // generateResponse comes from TextRuntime — it is identical across backends. These two are
    // not: LiteRT-LM fixes its sampler when a conversation opens, so changing either reopens
    // the conversation and loses its prefilled state. The llama.cpp equivalent rebuilds a
    // session instead, and a backend change there costs a full model reload.
    fun updateGenerationParameters(parameters: GenerationParameters)
    fun updateRuntimeSettings(settings: RuntimeSettings)
}

// No embedding counterpart to :backends:llamacpp's LlamaCppEmbeddingRuntime: LiteRT-LM's C
// API exposes tokenize, detokenize and scoring, but nothing that returns an embedding
// vector. An embedding backend would go through LiteRT core instead.
