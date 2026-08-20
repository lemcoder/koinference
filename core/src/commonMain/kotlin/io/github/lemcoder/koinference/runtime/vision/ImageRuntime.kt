package io.github.lemcoder.koinference.runtime.vision

import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.prompt.promptOf

/**
 * A runtime that answers a prompt with an image.
 *
 * The counterpart of `TextRuntime`, and named for its *output* the same way: a prompt may already
 * contain images — `PromptPart.ImageFile` and `ImageBytes` have been there from the start — so what
 * distinguishes this is what comes back.
 *
 * Worth being clear about what this is *not*. A vision-language model that reads an image and
 * answers in words is a `TextRuntime` handed `PromptPart.ImageFile`; it needs nothing here. This is
 * for a model whose output is pixels.
 */
interface ImageRuntime {

    /**
     * Generate an image.
     *
     * @throws UnsupportedOperationException if the backend cannot handle a part it was given.
     */
    suspend fun generateImage(prompt: List<PromptPart>): GeneratedImage

    /** Shorthand for a plain text prompt. */
    suspend fun generateImage(prompt: String): GeneratedImage = generateImage(promptOf(prompt))
}
