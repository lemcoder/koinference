package io.github.lemcoder.koinference

/**
 * A runtime that answers a prompt with text.
 *
 * The name is about the *output*: prompts may be multimodal, and the sibling axis is
 * embeddings, not images. This is the one thing every generative backend does identically —
 * same input types, same return type, same constraint type — so it is the only part of a
 * runtime that lives in `:core`.
 *
 * Deliberately not here: parameter and settings updates. Their signatures match across
 * backends but their contracts do not — LiteRT-LM reopens its conversation, llama.cpp rebuilds
 * its session and, for a backend change, reloads the model — and the parameter types
 * themselves differ per backend. Those stay concrete on each implementation until something
 * needs to vary them polymorphically.
 */
interface TextRuntime : ModelRuntime {

    /**
     * Generate a reply to a multimodal prompt.
     *
     * @param constraint restricts the output, e.g. to a JSON schema.
     * @throws UnsupportedOperationException if the backend cannot handle a part it was given.
     *         Backends reject rather than silently dropping parts — a prompt quietly missing
     *         its image reads as a bad model, not a bad call.
     */
    suspend fun generateResponse(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint? = null,
    ): String

    /** Shorthand for a plain text prompt. */
    suspend fun generateResponse(
        prompt: String,
        constraint: GenerationConstraint? = null,
    ): String = generateResponse(promptOf(prompt), constraint)
}
