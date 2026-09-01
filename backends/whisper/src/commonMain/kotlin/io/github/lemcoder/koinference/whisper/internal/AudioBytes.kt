package io.github.lemcoder.koinference.whisper.internal

/** Reads a file this backend was pointed at. An interface so audio decoding is testable. */
internal interface AudioBytes {

    fun read(path: String): ByteArray
}

/** The real filesystem. */
internal expect fun platformAudioBytes(): AudioBytes
