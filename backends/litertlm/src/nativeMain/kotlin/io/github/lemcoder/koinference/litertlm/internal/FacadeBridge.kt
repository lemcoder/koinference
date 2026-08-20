@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.litertlm.internal

import cnames.structs.KoiLmConversation
import cnames.structs.KoiLmEngine
import io.github.lemcoder.koinference.Accelerator
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

internal object FacadeBridge : LiteRtLmBridge {
    override fun openEngine(options: EngineOptions): LiteRtLmEngine {
        val handle = koilm_model_load(
            options.modelPath,
            options.cacheDir,
            // toInt: cinterop gives an anonymous C enum's constants as UInt, and the
            // parameter they are for is a plain int.
            when (options.accelerator) {
                Accelerator.CPU -> KOILM_BACKEND_CPU
                Accelerator.GPU -> KOILM_BACKEND_GPU
            }.toInt(),
            options.nThreads,
            options.maxTokens,
        )
        checkNotNull(handle) { "Could not load ${options.modelPath}: ${lastError()}" }
        return FacadeEngine(handle)
    }
}
