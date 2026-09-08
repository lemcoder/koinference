package io.github.lemcoder.koinference.runtime

import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.prompt.promptOf
import io.github.lemcoder.koinference.runtime.generation.GenerationConstraint
import io.github.lemcoder.koinference.runtime.generation.GenerationParameters
import io.github.lemcoder.koinference.runtime.media.ResponsePart

/**
 * A connection that answers a prompt, in [ResponsePart]s.
 *
 * A reply can carry text and audio interleaved, so `String` is not the shape of a reply — it is the
 * shape of one kind of part. A caller wanting the text of a text model writes the filter itself, and
 * can see it is dropping anything else.
 *
 * **Streaming is a callback, not a `Flow`.** A KMP library is consumed from Swift and Java, where
 * `Flow` does not bridge; a Kotlin consumer who wants a `Flow` wraps [generate] with
 * `callbackFlow { }`. See the public-API rule in `CLAUDE.md`.
 */
interface GeneratingConnection : Connection {

    /** The sampling parameters the next generation will use. */
    val generationParameters: GenerationParameters

    /**
     * Generate a reply, delivering each [ResponsePart] to [onPart] as the backend produces it.
     *
     * Suspends until the reply is complete; the parts passed to [onPart] concatenate, in order, to
     * the whole reply. A part is one backend step — a token for llama.cpp — so parts are events, not
     * tokens. Backends fail on a prompt part they cannot send rather than dropping it.
     *
     * @throws KoinferenceException.ConnectionClosed if this connection is closed.
     * @throws KoinferenceException.GenerationFailed if the generation itself fails.
     */
    suspend fun generate(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint? = null,
        onPart: (ResponsePart) -> Unit,
    )

    /**
     * Change the sampling parameters.
     *
     * Discards whatever decode state the connection had prepared (a session, its sampler): the
     * sampler is fixed when the context is opened, so a change rebuilds it. A backend applies the
     * subset it supports and ignores the rest, never substituting one knob for another;
     * [io.github.lemcoder.koinference.backend.Backend.honours] says which it applies.
     */
    suspend fun updateGenerationParameters(parameters: GenerationParameters)

    /** Collect the whole reply into a list. Convenience over [generate]. */
    suspend fun generateAll(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint? = null,
    ): List<ResponsePart> = buildList { generate(prompt, constraint) { add(it) } }

    /** Shorthand for a plain-text prompt. */
    suspend fun generate(prompt: String, onPart: (ResponsePart) -> Unit) =
        generate(promptOf(prompt), null, onPart)

    /** Shorthand for a plain-text prompt. */
    suspend fun generateAll(prompt: String): List<ResponsePart> = generateAll(promptOf(prompt), null)
}
