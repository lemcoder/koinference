package io.github.lemcoder.koinference.llamacpp.internal

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
internal actual fun platformBridge(): LlamaCppBridge = JniBridge
