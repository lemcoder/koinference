package io.github.lemcoder.koinference.runtime

import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.prompt.promptOf

/**
 * A runtime that answers a prompt with text.
 *
 * The name is about the *output*: prompts may be multimodal, and the sibling axis is
 * embeddings, not images. This is the one thing every generative backend does identically —
 * same input types, same return type, same constraint type — so it is the only part of a
 * runtime that lives in `:core`.
 *
 * Parameter and settings updates used to be excluded from `:core` on the grounds that their
 * signatures matched but their contracts did not. They differ in cost — LiteRT-LM reopens a
 * conversation, llama.cpp rebuilds a session and may reload the model — which one contract can
 * state, so they live on [ModelRuntime] now. Both backends had declared them identically.
 */
interface TextRuntime : ModelRuntime {

    /**
     * Generate a reply to a multimodal prompt.
     *
     * @param constraint restricts the output, e.g. to a JSON schema.
     * Backends fail on a part they cannot send rather than dropping it: a prompt quietly
     * missing its image reads as a bad model rather than a bad call.
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
