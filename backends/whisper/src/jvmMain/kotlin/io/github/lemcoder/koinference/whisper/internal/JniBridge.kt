package io.github.lemcoder.koinference.whisper.internal

import io.github.lemcoder.koinference.whisper.jni.kniBridge0
import io.github.lemcoder.koinference.whisper.jni.kniBridge1
import io.github.lemcoder.koinference.whisper.jni.kniBridge7

// The kniBridgeN functions are generated from native/facade/koinference_whisper.h by the Konan
// plugin, numbered by the header's declaration order — see the generated file's `/** C: … */`
// comments, and docs/backends.md on why that order is an ABI.
//
// This file is duplicated verbatim in the other ART leg. Deliberate: the generated bridges land in
// each target's own source set, so a shared parent could not see them.

/** A transcript segment cannot outgrow this; whisper's are a sentence or so. */
internal const val SEGMENT_BYTES = 4096

/** Long enough for `whisper_print_system_info` and any failure message. */
internal const val INFO_BYTES = 1024

internal object JniBridge : WhisperBridge {

    override fun openModel(options: WhisperModelOptions): WhisperModel {
        val handle = kniBridge1(options.modelPath, if (options.useGpu) 1 else 0)
        check(handle != 0L) { "whisper could not load ${options.modelPath}: ${lastError()}" }
        return JniModel(handle)
    }
}

/** Whatever the facade recorded on this thread, for a message worth reading. */
internal fun lastError(): String {
    val buffer = ByteArray(INFO_BYTES)
    val size = kniBridge7(buffer, buffer.size)
    return if (size <= 0) "no detail" else buffer.decodeToString(0, minOf(size, buffer.size - 1))
}

/** What whisper.cpp was compiled with. Unused by the runtime; kept for a results file to record. */
internal fun whisperSystemInfo(): String {
    val buffer = ByteArray(INFO_BYTES)
    val size = kniBridge0(buffer, buffer.size)
    return if (size <= 0) "" else buffer.decodeToString(0, minOf(size, buffer.size - 1))
}
