@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.llamacpp.internal

import cnames.structs.KoiModel
import cnames.structs.KoiSession
import io.github.lemcoder.koinference.InferenceBackend
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
import koinference.koi_session_free
import koinference.koi_token_count
import kotlinx.cinterop.ByteVar
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
private const val LARGE_BUFFER_BYTES = 1 shl 20

/** One chunk is a single token; the facade errors rather than truncating past this. */
private const val CHUNK_BYTES = 512

internal actual fun platformBridge(): LlamaCppBridge = FacadeBridge

private object FacadeBridge : LlamaCppBridge {

    // koi_backend_init has to run before the first model load, and llama_backend_free tears down
    // state every live model and session still points at — with no ownership information here to
    // reference-count, since a second loader may hold a model this one knows nothing about. So it
    // is initialised once and never freed: a fixed set of globals, released by the process
    // exiting. `lazy` is synchronised by default, and two coroutines loading models on different
    // threads must not both call init.
    private val backendInit: Unit by lazy { koi_backend_init() }

    override fun openModel(options: ModelOptions): LlamaCppModel {
        backendInit
        val handle = koi_model_load(
            options.modelPath,
            when (options.backend) {
                InferenceBackend.CPU -> 0
                InferenceBackend.GPU -> ALL_GPU_LAYERS
            },
        )
        checkNotNull(handle) { "llama.cpp could not load ${options.modelPath}" }
        return FacadeModel(handle)
    }

    override fun jsonSchemaToGrammar(schema: String): String = memScoped {
        val buffer = allocArray<ByteVar>(LARGE_BUFFER_BYTES)
        val written = koi_json_schema_to_grammar(schema, buffer, LARGE_BUFFER_BYTES)
        require(written > 0) { "Not a convertible JSON schema: $schema" }
        buffer.toKString()
    }
}

private class FacadeModel(private val handle: CPointer<KoiModel>) : LlamaCppModel {

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

private class FacadeSession(private val handle: CPointer<KoiSession>) : LlamaCppSession {

    override fun generate(systemPrompt: String?, prompt: String, grammar: String?): String =
        memScoped {
            val buffer = allocArray<ByteVar>(LARGE_BUFFER_BYTES)
            val written = koi_generate(
                handle, systemPrompt, prompt, grammar, buffer, LARGE_BUFFER_BYTES,
            )
            // A -1 is an empty string otherwise, which is indistinguishable from a model that
            // produced nothing.
            check(written >= 0) { "llama.cpp generation failed" }
            buffer.toKString()
        }

    /**
     * Pulls chunks from the facade's `koi_generate_begin`/`_next`/`_end` loop.
     *
     * The same loop shape `:backends:litertlm` uses, so the code that times it is identical for
     * both engines. The `finally` matters: a caller that stops collecting would otherwise leave
     * the session mid-decode for the next one.
     */
    override fun stream(systemPrompt: String?, prompt: String, grammar: String?): Flow<String> =
        flow {
            check(koi_generate_begin(handle, systemPrompt, prompt, grammar) >= 0) {
                "llama.cpp could not start generating"
            }
            try {
                while (true) {
                    // withContext per token rather than around the loop: emitting has to happen in
                    // the flow's own context, and the decode is what belongs on Default.
                    val chunk = withContext(Dispatchers.Default) { nextChunk() } ?: break
                    emit(chunk)
                }
            } finally {
                koi_generate_end(handle)
            }
        }

    private fun nextChunk(): String? = memScoped {
        val buffer = allocArray<ByteVar>(CHUNK_BYTES)
        val written = koi_generate_next(handle, buffer, CHUNK_BYTES)
        check(written >= 0) { "llama.cpp streaming failed" }
        if (written == 0) null else buffer.toKString()
    }

    override fun tokenCount(text: String): Int {
        val count = koi_token_count(handle, text)
        check(count >= 0) { "llama.cpp could not tokenize" }
        return count
    }

    override fun close() {
        koi_session_free(handle)
    }
}
