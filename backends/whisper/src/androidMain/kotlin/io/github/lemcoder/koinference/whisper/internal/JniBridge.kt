package io.github.lemcoder.koinference.whisper.internal

import koinference_whisper.koiw_last_error
import koinference_whisper.koiw_model_load
import koinference_whisper.koiw_system_info

// The koiw_* functions are JNI bindings the Konan plugin generates from
// native/facade/koinference_whisper.h, one per C function and named after it.
//
// This file is duplicated verbatim in the other ART leg. Deliberate: the generated bridges land in
// each target's own source set, so a shared parent could not see them.

/** A transcript segment cannot outgrow this; whisper's are a sentence or so. */
internal const val SEGMENT_BYTES = 4096

/** Long enough for `whisper_print_system_info` and any failure message. */
internal const val INFO_BYTES = 1024

internal object JniBridge : WhisperBridge {

    override fun openModel(options: WhisperModelOptions): WhisperModel {
        val handle = koiw_model_load(options.modelPath, if (options.useGpu) 1 else 0)
        check(handle != 0L) { "whisper could not load ${options.modelPath}: ${lastError()}" }
        return JniModel(handle)
    }
}

/** Whatever the facade recorded on this thread, for a message worth reading. */
internal fun lastError(): String {
    val buffer = ByteArray(INFO_BYTES)
    val size = koiw_last_error(buffer, buffer.size)
    return if (size <= 0) "no detail" else buffer.decodeToString(0, minOf(size, buffer.size - 1))
}

/** What whisper.cpp was compiled with. Unused by the runtime; kept for a results file to record. */
internal fun whisperSystemInfo(): String {
    val buffer = ByteArray(INFO_BYTES)
    val size = koiw_system_info(buffer, buffer.size)
    return if (size <= 0) "" else buffer.decodeToString(0, minOf(size, buffer.size - 1))
}
