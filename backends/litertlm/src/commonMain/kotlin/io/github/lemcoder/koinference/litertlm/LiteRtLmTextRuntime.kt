package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.runtime.GenerationParameters
import io.github.lemcoder.koinference.runtime.ModelRuntime
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import io.github.lemcoder.koinference.runtime.StreamingTextRuntime
import io.github.lemcoder.koinference.runtime.TextRuntime
import io.github.lemcoder.koinference.runtime.TokenCounting

/**
 * What a loaded LiteRT-LM model can do.
 *
 * Text only, and no sealed parent to hold it apart from anything else: LiteRT-LM's C API exposes
 * tokenize, detokenize and scoring, but nothing that returns an embedding vector, so there is
 * only ever one kind of runtime here. An embedding backend would go through LiteRT core instead.
 */
interface LiteRtLmTextRuntime : ModelRuntime, TextRuntime, StreamingTextRuntime, TokenCounting {

    /** What the next conversation will be opened with. */
    val generationParameters: GenerationParameters

    /** Where the model is currently running. */
    val runtimeSettings: RuntimeSettings

    // generateResponse comes from TextRuntime — it is identical across backends. These two are
    // not: LiteRT-LM fixes its sampler when a conversation opens, so changing either reopens the
    // conversation and loses its prefilled state. The llama.cpp equivalent rebuilds a session
    // instead, and reads a different subset of GenerationParameters.
    //
    // Both suspend. Neither is a field assignment: they free native memory a generation may be
    // using, so they wait for it rather than race it, and a backend change reloads the weights.
    suspend fun updateGenerationParameters(parameters: GenerationParameters)

    suspend fun updateRuntimeSettings(settings: RuntimeSettings)

    /**
     * Forget the conversation so far and start the next turn from the system prompt.
     *
     * The weights stay loaded, so this is the cheap way to begin a new chat — and the only way,
     * short of changing a parameter for the side effect of dropping the history.
     */
    suspend fun resetConversation()
}
