package io.github.lemcoder.koinference.runtime

import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.prompt.promptOf
import kotlinx.coroutines.flow.Flow

/**
 * A loaded model that answers a prompt.
 *
 * Both paths speak [ResponsePart], and there is no text-flavoured shortcut over them. A reply can
 * carry text and audio interleaved, so `String` is not the shape of a reply — it is the shape of one
 * kind of part. An earlier version had `generateResponse(): String` and a `streamText` filter, and
 * every caller that used them would have silently dropped the audio of a model that produced any.
 *
 * A caller that wants the text of a text model writes the filter itself, and can see that it is
 * dropping something:
 *
 * ```kotlin
 * val text = runtime.generateResponse("…")
 *     .filterIsInstance<ResponsePart.Text>()
 *     .joinToString("") { it.text }
 * ```
 */
interface GeneratingRuntime : ModelRuntime {

    /**
     * Generate a whole reply.
     *
     * Backends fail on a prompt part they cannot send rather than dropping it: a prompt quietly
     * missing its image reads as a bad model rather than a bad call.
     */
    suspend fun generateResponse(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint? = null,
    ): List<ResponsePart>

    /**
     * Generate a reply, emitting each part as the backend produces it.
     *
     * Cold: generation starts on collection, and cancelling the collection stops it. The parts
     * concatenate to exactly what [generateResponse] would have returned.
     *
     * A part is whatever the backend emits in one step — a single token for llama.cpp, which decodes
     * one at a time — so parts are comparable as *events*, not as tokens. Anything needing a token
     * count gets it from a tokenizer.
     */
    fun streamResponse(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint? = null,
    ): Flow<ResponsePart>

    /** Shorthand for a plain text prompt. Still returns parts. */
    suspend fun generateResponse(
        prompt: String,
        constraint: GenerationConstraint? = null,
    ): List<ResponsePart> = generateResponse(promptOf(prompt), constraint)

    /** Shorthand for a plain text prompt. Still returns parts. */
    fun streamResponse(
        prompt: String,
        constraint: GenerationConstraint? = null,
    ): Flow<ResponsePart> = streamResponse(promptOf(prompt), constraint)
}
