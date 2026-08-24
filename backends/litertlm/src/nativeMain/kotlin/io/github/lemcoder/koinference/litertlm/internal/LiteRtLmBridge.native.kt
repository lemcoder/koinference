@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.litertlm.internal

import cnames.structs.KoiLmConversation
import cnames.structs.KoiLmEngine
import io.github.lemcoder.koinference.runtime.Accelerator
// The cinterop package is named after the .def file, not the interop, so it is
// koinference_litertlm rather than koinferenceLiteRtLm.
import koinference_litertlm.KOILM_BACKEND_CPU
import koinference_litertlm.KOILM_BACKEND_GPU
import koinference_litertlm.KoiLmSessionParams
import koinference_litertlm.koilm_stream_begin
import koinference_litertlm.koilm_token_count
import koinference_litertlm.koilm_stream_next
import koinference_litertlm.koilm_stream_end
import koinference_litertlm.koilm_generate
import koinference_litertlm.koilm_last_error
import koinference_litertlm.koilm_last_response
import koinference_litertlm.koilm_model_free
import koinference_litertlm.koilm_model_load
import koinference_litertlm.koilm_session_create
import koinference_litertlm.koilm_session_free
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.cinterop.toKString

/**
 * 64 KiB. Replies are JSON-wrapped, so this is the envelope size, not the token budget — and
 * it is only the first guess: a longer reply is collected from the facade rather than lost.
 */
internal const val INITIAL_REPLY_BYTES = 1 shl 16
/** Leaves the runtime's own seeding, matching koilm_default_session_params(). */

internal actual fun platformBridge(): LiteRtLmBridge = FacadeBridge
internal fun lastError(): String = koilm_last_error()?.toKString().orEmpty()
// One chunk is a token or a few; the facade errors rather than truncating past this.
internal const val CHUNK_BYTES = 512
