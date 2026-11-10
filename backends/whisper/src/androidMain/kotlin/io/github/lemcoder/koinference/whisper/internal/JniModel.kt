package io.github.lemcoder.koinference.whisper.internal

import koinference_whisper.KoiwModelPointer
import koinference_whisper.koiw_model_free
import koinference_whisper.koiw_transcribe
import koinference_whisper.koiw_transcribe_begin
import koinference_whisper.koiw_transcribe_end
import koinference_whisper.koiw_transcribe_next
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext

// This file is duplicated verbatim in the other ART leg. Deliberate: the generated bridges land in
// each target's own source set, so a shared parent could not see them.

internal class JniModel(private val handle: KoiwModelPointer) : WhisperModel {

    override suspend fun transcribe(
        samples: FloatArray,
        options: WhisperTranscriptionOptions,
    ): String = withContext(Dispatchers.Default) {
        // snprintf's contract: ask once, and grow only if the transcript did not fit. A long
        // recording can outrun any fixed guess.
        val first = ByteArray(SEGMENT_BYTES)
        val needed = koiw_transcribe(
            handle, samples, samples.size, options.language, options.threads, first, first.size,
        )
        check(needed >= 0) { "whisper failed to transcribe: ${lastError()}" }

        if (needed < first.size) {
            first.decodeToString(0, needed)
        } else {
            val exact = ByteArray(needed + 1)
            val again = koiw_transcribe(
                handle, samples, samples.size, options.language, options.threads, exact, exact.size,
            )
            check(again >= 0) { "whisper failed to transcribe: ${lastError()}" }
            exact.decodeToString(0, minOf(again, exact.size - 1))
        }
    }

    override fun stream(
        samples: FloatArray,
        options: WhisperTranscriptionOptions,
    ): Flow<String> = channelFlow {
        val stream = koiw_transcribe_begin(handle, samples, samples.size, options.language, options.threads)
        checkNotNull(stream) { "whisper could not start transcribing: ${lastError()}" }

        try {
            withContext(Dispatchers.IO) {
                // Blocking pulls on an IO thread: the facade parks here until whisper's own thread
                // produces a segment, which is what makes time to first segment mean anything.
                val buffer = ByteArray(SEGMENT_BYTES)
                while (true) {
                    val size = koiw_transcribe_next(stream, buffer, buffer.size)
                    if (size == 0) break
                    check(size > 0) { "whisper failed mid-transcription: ${lastError()}" }
                    trySendBlocking(buffer.decodeToString(0, minOf(size, buffer.size - 1)))
                }
            }
        } finally {
            // Joins whisper's thread. Skipping it on cancellation would leave it writing into a
            // queue nobody drains.
            koiw_transcribe_end(stream)
        }
    }

    override fun close() = koiw_model_free(handle)
}

