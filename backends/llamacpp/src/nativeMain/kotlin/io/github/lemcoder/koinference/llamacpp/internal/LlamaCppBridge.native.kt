@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.llamacpp.internal

import cnames.structs.KoiModel
import cnames.structs.KoiSession
import io.github.lemcoder.koinference.runtime.Accelerator
import koinference.KoiSessionParams
import koinference.koi_backend_init
import koinference.koi_generate
import koinference.koi_generate_begin
import koinference.koi_generate_end
import koinference.koi_generate_next
import koinference.koi_json_schema_to_grammar
import koinference.koi_model_free
import koinference.koi_model_load
import koinference.koi_session_create
import koinference.koi_session_cpu_mask
import koinference.koi_session_free
import koinference.koi_session_set_cpu_mask
import koinference.koi_token_count
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** 1 MiB. A grammar from a nested schema outgrows anything smaller, and so does a long reply. */
internal const val LARGE_BUFFER_BYTES = 1 shl 20
/** One chunk is a single token; the facade errors rather than truncating past this. */
internal const val CHUNK_BYTES = 512
/** More cores than any phone has, so the mask is never truncated on the way out. */
internal const val MAX_MASK_CPUS = 64
internal actual fun platformBridge(): LlamaCppBridge = FacadeBridge
