package io.github.lemcoder.koinference.whisper.internal

import kotlinx.coroutines.flow.Flow

/**
 * Loaded whisper weights.
 *
 * **No session tier here, unlike the other backends, and that is a fact about whisper rather than a
 * shortcut.** `whisper_full` carries nothing between calls: a transcription is the audio it was
 * given and nothing else. The other engines need a session because they hold a KV cache that
 * outlives a turn — three of them turned out to carry it further than intended — and inventing one
 * here would be a tier that exists only to look like the others.
 */
internal interface WhisperModel {

    /** The whole transcript. Drains the same segment loop [stream] pulls from. */
    suspend fun transcribe(samples: FloatArray, options: WhisperTranscriptionOptions): String

    /** Segments as whisper produces them, which is what makes time to first token mean anything. */
    fun stream(samples: FloatArray, options: WhisperTranscriptionOptions): Flow<String>

    fun close()
}
