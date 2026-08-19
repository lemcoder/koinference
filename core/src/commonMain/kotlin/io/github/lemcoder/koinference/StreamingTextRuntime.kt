package io.github.lemcoder.koinference

import kotlinx.coroutines.flow.Flow

/**
 * A runtime that emits its reply as it is produced.
 *
 * Worth having on its own merits — a caller showing text as it arrives needs this — and it is
 * also the only thing a benchmark needs from a backend. Given a first chunk, time to first
 * token is something the *caller* can measure, with one clock and one code path across every
 * engine; without it, each backend would have to report its own timings and a comparison would
 * be between measurements taken in different places.
 *
 * A chunk is whatever the backend emits in one step. That is a single token for llama.cpp,
 * which decodes one at a time, and whatever LiteRT-LM chooses to hand back. Chunks are
 * therefore comparable as *events*, not as tokens; anything that needs a token count has to
 * get it from a tokenizer rather than from counting these.
 */
interface StreamingTextRuntime : ModelRuntime {

    /**
     * Generate a reply, emitting each chunk as the backend produces it.
     *
     * The flow is cold: generation starts on collection. Cancelling the collection stops the
     * generation. The concatenation of every chunk is exactly what the non-streaming call
     * would have returned.
     *
     * Backends fail on a part they cannot send rather than dropping it.
     */
    fun streamResponse(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint? = null,
    ): Flow<String>

    /** Shorthand for a plain text prompt. */
    fun streamResponse(
        prompt: String,
        constraint: GenerationConstraint? = null,
    ): Flow<String> = streamResponse(promptOf(prompt), constraint)
}
