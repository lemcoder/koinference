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

internal class FacadeModel(private val handle: CPointer<KoiModel>) : LlamaCppModel {

    override fun openSession(options: SessionOptions): LlamaCppSession {
        // koi_default_session_params() hands back a CValue — an immutable off-heap snapshot — so
        // its fields cannot be assigned through it. Every field is set here anyway.
        val params = cValue<KoiSessionParams> {
            n_ctx = options.nCtx
            n_threads = options.nThreads
            n_predict = options.nPredict
            temp = options.temperature
            top_k = options.topK
            min_p = options.minP
        }
        val session = koi_session_create(handle, params)
        checkNotNull(session) { "llama.cpp could not create a session" }
        return FacadeSession(session)
    }

    override fun close() {
        koi_model_free(handle)
    }
}
