package io.github.lemcoder.koinference.runtime

/**
 * A loaded model, and the settings it was loaded with.
 *
 * Every backend fixes its sampler and its device when something is opened rather than per request,
 * so every backend needs a way to say what those are and to change them. Both did, with the same
 * four members and near-identical KDoc arguing they could not be shared — [TextRuntime] used to
 * say the signatures matched but the contracts did not.
 *
 * They differ in *cost*, not in meaning. llama.cpp rebuilds a session and, for a device change,
 * reloads the weights; LiteRT-LM reopens a conversation and loses its prefilled history. Both are
 * "this may throw away work the engine had prepared", which one contract can state — and stating
 * it here is what lets a caller holding whatever [ModelLoader.load] returned retune it without
 * knowing which engine answered.
 *
 * What stays on a backend's own interface is what only that backend has: LiteRT-LM's
 * `resetConversation` has no llama.cpp counterpart, so it is not here.
 */
interface ModelRuntime {

    /** What the next session, conversation, or whatever this engine opens will be created with. */
    val generationParameters: GenerationParameters

    /** Where the model is currently running. */
    val runtimeSettings: RuntimeSettings

    /**
     * Change the sampling parameters.
     *
     * Suspends, and not out of caution: engines fix their sampler when they open something, so this
     * discards whatever was open — a session, a conversation and its prefilled history — and the
     * memory being discarded may be in use by a generation that has to finish first.
     *
     * A backend applies the subset of [GenerationParameters] it supports and ignores the rest. It
     * never substitutes one knob for another, and [io.github.lemcoder.koinference.backend.Backend.honours]
     * says which ones it applies.
     */
    suspend fun updateGenerationParameters(parameters: GenerationParameters)

    /**
     * Move the model, or try to.
     *
     * The expensive one: where a model runs is decided when its weights are loaded on both engines
     * today, so this can mean reloading them. If that fails the runtime is left unloaded and says
     * so, rather than reporting a device it is not running on.
     */
    suspend fun updateRuntimeSettings(settings: RuntimeSettings)
}
