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

internal class FacadeConversation(
    private val handle: CPointer<KoiLmConversation>,
) : LiteRtLmConversation {

    override fun generate(prompt: String, jsonSchema: String?): String = memScoped {
        val buffer = allocArray<ByteVar>(INITIAL_REPLY_BYTES)
        val needed = koilm_generate(handle, prompt, jsonSchema, buffer, INITIAL_REPLY_BYTES)
        check(needed >= 0) { "LiteRT-LM generation failed: ${lastError()}" }

        // needed is what the reply wants, not what was written. A reply longer than the first
        // guess is still held by the facade, so it is collected rather than regenerated —
        // generating again would add a second user turn to the conversation.
        val raw = if (needed < INITIAL_REPLY_BYTES) {
            buffer.toKString()
        } else {
            val size = needed + 1
            val grown = allocArray<ByteVar>(size)
            val collected = koilm_last_response(grown, size)
            check(collected == needed) {
                "LiteRT-LM reply changed size while being collected: $needed then $collected"
            }
            grown.toKString()
        }
        extractResponseText(raw)
    }

    /**
     * Pulls chunks from the facade, which buffers what the runtime pushes from its own thread.
     *
     * The same loop shape the llama.cpp binding uses, so the code that times it is identical
     * for both engines. `koilm_stream_next` blocks until a chunk exists, so this runs on
     * Dispatchers.Default rather than wherever the collector happens to be.
     */
    override fun stream(prompt: String, jsonSchema: String?): Flow<String> = flow {
        check(koilm_stream_begin(handle, prompt, jsonSchema) == 0) {
            "LiteRT-LM could not start streaming: ${lastError()}"
        }
        try {
            while (true) {
                val chunk = withContext(Dispatchers.Default) { nextChunk() } ?: break
                // Each chunk is the same {role, content} envelope the blocking reply uses,
                // carrying that step's text — a delta, not the reply so far. Unwrapped with the
                // same reader, so streamed and blocking text cannot diverge.
                val text = extractResponseText(chunk)
                if (text.isNotEmpty()) emit(text)
            }
        } finally {
            // Also on cancellation: the runtime is still decoding, and dropping the state
            // without cancelling would leave it writing into a freed buffer.
            koilm_stream_end(handle)
        }
    }

    private fun nextChunk(): String? = memScoped {
        val buffer = allocArray<ByteVar>(CHUNK_BYTES)
        val written = koilm_stream_next(handle, buffer, CHUNK_BYTES)
        check(written >= 0) { "LiteRT-LM streaming failed: ${lastError()}" }
        if (written == 0) null else buffer.toKString()
    }

    override fun close() {
        koilm_session_free(handle)
    }
}
