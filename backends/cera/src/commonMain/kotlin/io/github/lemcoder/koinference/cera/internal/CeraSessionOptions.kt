package io.github.lemcoder.koinference.cera.internal

/**
 * What opening a session and decoding needs.
 *
 * [seed] is on the session rather than the generation because that is where Cera puts it, which is
 * also why this backend can claim [io.github.lemcoder.koinference.backend.SamplingKnob.SEED] at all.
 */
internal data class CeraSessionOptions(
    /** Prepended as a system turn when the model's template has a role for one. */
    val systemPrompt: String?,
    val maxOutputTokens: Int,
    val contextTokens: Int,
    val temperature: Double?,
    val topK: Int?,
    val topP: Double?,
    val minP: Double?,
    val seed: Int?,
)
