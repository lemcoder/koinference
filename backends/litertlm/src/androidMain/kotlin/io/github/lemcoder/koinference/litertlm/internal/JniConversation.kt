package io.github.lemcoder.koinference.litertlm.internal

import io.github.lemcoder.koinference.runtime.generation.Accelerator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import koinference_litertlm.koilm_generate
import koinference_litertlm.koilm_last_response
import koinference_litertlm.koilm_session_free
import koinference_litertlm.koilm_stream_begin
import koinference_litertlm.koilm_stream_end
import koinference_litertlm.koilm_stream_next
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

internal class JniConversation(private val handle: Long) : LiteRtLmConversation {

    override fun generate(prompt: String, jsonSchema: String?): String {
        val buffer = ByteArray(INITIAL_REPLY_BYTES)
        val needed = koilm_generate(handle, prompt, jsonSchema, buffer, buffer.size)
        check(needed >= 0) { "LiteRT-LM generation failed: ${lastError()}" }

        // needed is what the reply wants, not what was written. A reply longer than the first
        // guess is still held by the facade, so it is collected rather than regenerated —
        // generating again would add a second user turn to the conversation.
        val raw = if (needed < buffer.size) {
            String(buffer, 0, needed, Charsets.UTF_8)
        } else {
            val grown = ByteArray(needed + 1)
            val collected = koilm_last_response(grown, grown.size)
            check(collected == needed) {
                "LiteRT-LM reply changed size while being collected: $needed then $collected"
            }
            String(grown, 0, collected, Charsets.UTF_8)
        }
        return extractResponseText(raw)
    }

    /**
     * Pulls chunks from the facade, which buffers what the runtime pushes from its own thread.
     *
     * Identical in shape to the Apple binding, deliberately: one Kotlin implementation drives
     * both, and the harness timing it cannot tell them apart. `koilm_stream_next` blocks until a
     * chunk exists, so it runs on Dispatchers.IO rather than on the collector's thread.
     */
    override fun stream(prompt: String, jsonSchema: String?): Flow<String> = flow {
        check(koilm_stream_begin(handle, prompt, jsonSchema) == 0) {
            "LiteRT-LM could not start streaming: ${lastError()}"
        }
        try {
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                val written = withContext(Dispatchers.IO) { koilm_stream_next(handle, buffer, buffer.size) }
                check(written >= 0) { "LiteRT-LM streaming failed: ${lastError()}" }
                if (written == 0) break

                // Each chunk is the same {role, content} envelope the blocking reply uses,
                // carrying that step's text. Unwrapped with the same reader, so streamed and
                // blocking text cannot diverge.
                val text = extractResponseText(String(buffer, 0, written, Charsets.UTF_8))
                if (text.isNotEmpty()) emit(text)
            }
        } finally {
            // Also on cancellation: the runtime is still decoding, and dropping the state without
            // cancelling would leave it writing into a freed buffer.
            koilm_stream_end(handle)
        }
    }

    override fun close() = koilm_session_free(handle)
}
