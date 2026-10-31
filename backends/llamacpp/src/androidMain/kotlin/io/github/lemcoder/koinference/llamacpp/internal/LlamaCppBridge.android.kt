package io.github.lemcoder.koinference.llamacpp.internal

import android.os.Build
import io.github.lemcoder.koinference.backend.BackendUnsupportedException
import io.github.lemcoder.koinference.runtime.generation.Accelerator
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import koinference.koi_backend_init
import koinference.koi_generate
import koinference.koi_generate_begin
import koinference.koi_generate_end
import koinference.koi_generate_next
import koinference.koi_json_schema_to_grammar
import koinference.koi_model_free
import koinference.koi_model_load
import koinference.koi_session_cpu_mask
import koinference.koi_session_create
import koinference.koi_session_free
import koinference.koi_session_set_cpu_mask
import koinference.koi_token_count
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

// The koi_* functions are JNI bindings the konan plugin generates from
// native/facade/koinference_facade.h, one per C function and named after it.
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
