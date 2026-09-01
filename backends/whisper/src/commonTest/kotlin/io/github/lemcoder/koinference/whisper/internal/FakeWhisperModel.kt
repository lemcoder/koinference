package io.github.lemcoder.koinference.whisper.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class FakeWhisperModel(
    val options: WhisperModelOptions,
    private val transcript: (FloatArray) -> String,
) : WhisperModel {

    val transcribed = mutableListOf<FloatArray>()
    val transcriptionOptions = mutableListOf<WhisperTranscriptionOptions>()

    var closed = false
        private set

    override suspend fun transcribe(
        samples: FloatArray,
        options: WhisperTranscriptionOptions,
    ): String {
        transcribed += samples
        transcriptionOptions += options
        return transcript(samples)
    }

    override fun stream(
        samples: FloatArray,
        options: WhisperTranscriptionOptions,
    ): Flow<String> = flow {
        transcribed += samples
        transcriptionOptions += options
        // Segment by segment, because whisper reports them as it produces them and a binding that
        // buffered would make time to first segment equal total latency.
        transcript(samples).split(" ").forEach { emit("$it ") }
    }

    override fun close() {
        closed = true
    }
}
