package io.github.lemcoder.koinference.whisper.internal

/** What loading whisper weights needs. */
internal data class WhisperModelOptions(
    val modelPath: String,
    val useGpu: Boolean,
)
