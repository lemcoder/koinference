package io.github.lemcoder.koinference.cera.internal

/**
 * Loaded weights, and the tokenizer that came with them.
 *
 * An engine hands out sessions, so a session cannot exist without the model it decodes from.
 */
internal interface CeraModel {

    /** Tokens in [text] by this model's own tokenizer: content only, no BOS and no chat template. */
    fun countTokens(text: String): Int

    fun openSession(options: CeraSessionOptions): CeraSession

    fun close()
}
