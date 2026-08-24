package io.github.lemcoder.koinference.runtime

/** Encodings a backend may hand back. Add one when a backend produces it, not before. */
enum class AudioFormat {
    PCM_16,
    WAV,
}
