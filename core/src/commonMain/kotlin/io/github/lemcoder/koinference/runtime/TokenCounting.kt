package io.github.lemcoder.koinference.runtime

/**
 * A runtime that can count tokens with the tokenizer its model actually uses.
 *
 * The point is that the caller does the counting, with the engine's own tokenizer, rather than
 * each engine reporting counts in its own terms. A benchmark can then divide by a number that
 * means the same thing on both sides — unlike streamed chunks, which are whatever an engine
 * chooses to emit, and unlike a character count, which is not a token count at all.
 *
 * Separate from [TextRuntime] because not every backend exposes a tokenizer: implementing this
 * is a claim that the number comes from the model's vocabulary.
 */
interface TokenCounting {

    /**
     * Tokens in [text].
     *
     * Counts as the model would when reading [text] as content. A prompt that the backend wraps
     * in a chat template tokenizes to more than this once the template is applied, so this is a
     * measure of the text, not of what a generation will prefill.
     */
    suspend fun countTokens(text: String): Int
}
