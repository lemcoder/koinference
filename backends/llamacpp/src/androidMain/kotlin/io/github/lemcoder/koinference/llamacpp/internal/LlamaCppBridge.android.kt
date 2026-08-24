package io.github.lemcoder.koinference.llamacpp.internal

import io.github.lemcoder.koinference.backend.BackendUnsupportedException
import io.github.lemcoder.koinference.runtime.Accelerator
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge0
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge3
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge4
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge6
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge7
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge8
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge10
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge11
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge12
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge13
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge14
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge15
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge16
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// The kniBridgeN functions are generated from native/facade/koinference_facade.h by the Konan
// plugin's `generateJvmInterop` task, numbered by the header's declaration order — see the
// generated file's `/** C: … */` comments. Index 5 is koi_default_session_params, which returns a
// struct by value and gets no bridge; index 9 is koi_embed, which nothing calls (see
// docs/backends.md on why it stays in the header).
//
// This file is duplicated verbatim in the other ART leg. That is deliberate — see
// docs/backends.md.

/** 1 MiB. A grammar from a nested schema outgrows anything smaller, and so does a long reply. */
internal const val LARGE_BUFFER_BYTES = 1 shl 20
/** One chunk is a single token; the facade errors rather than truncating past this. */
internal const val CHUNK_BYTES = 512
/** More cores than any phone has, so the mask is never truncated on the way out. */
internal const val MAX_MASK_CPUS = 64
/** Layout of `KoiSessionParams`: six 4-byte fields, no padding. */
internal const val SESSION_PARAMS_SIZE = 24
internal actual fun platformBridge(): LlamaCppBridge {
    unsupportedReason()?.let { throw BackendUnsupportedException("llama.cpp", it) }
    return JniBridge
}

/**
 * Why this device cannot run llama.cpp, or null when it can.
 *
 * Android only, and the only leg that needs it: this is the one binary that meets hardware it was
 * not built for. Two independent floors — the AAR's `minSdk 31`, reachable only through a consumer
 * that overrode it, and the dot-product extension the kernels are compiled to require. See
 * `docs/backends.md`.
 */
internal fun unsupportedReason(): String? {
    if (Build.VERSION.SDK_INT < MIN_SDK) {
        return "Android ${Build.VERSION.SDK_INT}; this backend is built for API $MIN_SDK and up"
    }
    if (!hasDotProduct()) {
        return "the CPU has no ARM dot-product extension (asimddp), which ggml's Q4_0 kernels are " +
            "compiled to require"
    }
    return null
}

private const val MIN_SDK = 31

/** Whether any core reports `asimddp`. Any, not all: `/proc/cpuinfo` has a Features line per core. */
private fun hasDotProduct(): Boolean =
    runCatching { File("/proc/cpuinfo").readText() }
        .getOrDefault("")
        .lineSequence()
        .filter { it.startsWith("Features") }
        .any { "asimddp" in it.split(':').last().split(' ') }
