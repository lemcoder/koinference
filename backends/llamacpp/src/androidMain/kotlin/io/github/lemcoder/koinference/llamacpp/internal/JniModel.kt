package io.github.lemcoder.koinference.llamacpp.internal

import io.github.lemcoder.koinference.runtime.generation.Accelerator
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

internal class JniModel(private val handle: Long) : LlamaCppModel {

    override fun openSession(options: SessionOptions): LlamaCppSession {
        // Packed by hand: the generator marshals a by-value struct as a byte array, so the field
        // order in the header is the contract.
        val params = ByteBuffer.allocate(SESSION_PARAMS_SIZE).order(ByteOrder.nativeOrder())
            .putInt(options.nCtx)
            .putInt(options.nThreads)
            .putInt(options.nPredict)
            .putFloat(options.temperature)
            .putInt(options.topK)
            .putFloat(options.minP)

        val session = koi_session_create(handle, params.array())
        check(session != 0L) { "llama.cpp could not create a session" }
        return JniSession(session)
    }

    override fun close() = koi_model_free(handle)
}
