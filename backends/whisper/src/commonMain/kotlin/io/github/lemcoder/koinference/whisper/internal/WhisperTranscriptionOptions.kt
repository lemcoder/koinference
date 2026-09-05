package io.github.lemcoder.koinference.whisper.internal

/**
 * What one transcription needs.
 *
 * Short, and honestly so: whisper's knobs are decoding strategy and language, not sampling. What
 * `GenerationParameters` offers has almost nowhere to go — see `Whisper.honours`.
 */
internal data class WhisperTranscriptionOptions(
    /** ISO code, or null to let whisper detect it. */
    val language: String?,
    val threads: Int,
)
