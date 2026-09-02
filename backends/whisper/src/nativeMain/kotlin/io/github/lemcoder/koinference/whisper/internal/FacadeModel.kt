package io.github.lemcoder.koinference.whisper.internal

import cnames.structs.KoiwModel
import koinference_whisper.koiw_model_free
import koinference_whisper.koiw_transcribe
import koinference_whisper.koiw_transcribe_begin
import koinference_whisper.koiw_transcribe_end
import koinference_whisper.koiw_transcribe_next
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pin
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext

@OptIn(ExperimentalForeignApi::class)
internal class FacadeModel(private val handle: CPointer<KoiwModel>) : WhisperModel {

    override suspend fun transcribe(
        samples: FloatArray,
        options: WhisperTranscriptionOptions,
    ): String = withContext(Dispatchers.Default) {
        samples.usePinned { pinned ->
            memScoped {
                // Asked once at a guess, then again at the size the facade reported — the same
                // snprintf contract the JNI leg follows.
                val first = allocArray<ByteVar>(SEGMENT_BYTES)
                val needed = koiw_transcribe(
                    handle, pinned.addressOf(0), samples.size, options.language,
                    options.threads, first, SEGMENT_BYTES,
                )
                check(needed >= 0) { "whisper failed to transcribe: ${lastError()}" }

                if (needed < SEGMENT_BYTES) {
                    first.toKString()
                } else {
                    val exact = allocArray<ByteVar>(needed + 1)
                    val again = koiw_transcribe(
                        handle, pinned.addressOf(0), samples.size, options.language,
                        options.threads, exact, needed + 1,
                    )
                    check(again >= 0) { "whisper failed to transcribe: ${lastError()}" }
                    exact.toKString()
                }
            }
        }
    }

    override fun stream(
        samples: FloatArray,
        options: WhisperTranscriptionOptions,
    ): Flow<String> = channelFlow {
        val pinned = samples.pin()
        val stream = koiw_transcribe_begin(
            handle, pinned.addressOf(0), samples.size, options.language, options.threads,
        )
        if (stream == null) {
            pinned.unpin()
            error("whisper could not start transcribing: ${lastError()}")
        }

        try {
            withContext(Dispatchers.Default) {
                memScoped {
                    val buffer = allocArray<ByteVar>(SEGMENT_BYTES)
                    while (true) {
                        val size = koiw_transcribe_next(stream, buffer, SEGMENT_BYTES)
                        if (size == 0) break
                        check(size > 0) { "whisper failed mid-transcription: ${lastError()}" }
                        trySendBlocking(buffer.toKString())
                    }
                }
            }
        } finally {
            koiw_transcribe_end(stream)
            // Only after the worker is joined: it reads these samples until then.
            pinned.unpin()
        }
    }

    override fun close() = koiw_model_free(handle)
}

