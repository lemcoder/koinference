package io.github.lemcoder.koinference.benchmark.result

import kotlinx.serialization.Serializable

@Serializable
data class InitializationMetrics(
    /** Process start to the harness taking its first measurement. Null off Android. */
    val processStartMs: Double? = null,
    val modelLoadMs: Double? = null,
    /**
     * Neither engine separates tokenizer setup from model loading, and neither is asked to
     * report it: it stays null rather than being carved out of modelLoadMs by guesswork.
     */
    val tokenizerInitMs: Double? = null,
)
