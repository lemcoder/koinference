package io.github.lemcoder.koinference.llamacpp.internal

import io.github.lemcoder.koinference.runtime.generation.Accelerator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import koinference.KoiSessionPointer
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

internal class JniSession(private val handle: KoiSessionPointer) : LlamaCppSession {

    override fun generate(systemPrompt: String?, prompt: String, grammar: String?): String {
        val out = ByteArray(LARGE_BUFFER_BYTES)
        val written = koi_generate(handle, systemPrompt, prompt, grammar, out, out.size)
        // A -1 is an empty string otherwise, which is indistinguishable from a model that
        // produced nothing.
        check(written >= 0) { "llama.cpp generation failed" }
        return String(out, 0, written, Charsets.UTF_8)
    }

    /**
     * Pulls chunks from the facade's `koi_generate_begin`/`_next`/`_end` loop.
     *
     * A pull loop rather than a callback because these are generated JNI bridges, which cannot
     * hand a C callback back into the JVM. The `finally` matters: a caller that stops collecting
     * would otherwise leave the session mid-decode for the next one.
     */
    override fun stream(systemPrompt: String?, prompt: String, grammar: String?): Flow<String> =
        flow {
            check(koi_generate_begin(handle, systemPrompt, prompt, grammar) >= 0) {
                "llama.cpp could not start generating"
            }
            try {
                val buffer = ByteArray(CHUNK_BYTES)
                while (true) {
                    // withContext per token rather than around the loop: emitting has to happen in
                    // the flow's own context, and the decode is what belongs on Default.
                    val written = withContext(Dispatchers.Default) {
                        koi_generate_next(handle, buffer, buffer.size)
                    }
                    check(written >= 0) { "llama.cpp streaming failed" }
                    if (written == 0) break
                    emit(String(buffer, 0, written, Charsets.UTF_8))
                }
            } finally {
                koi_generate_end(handle)
            }
        }

    override fun tokenCount(text: String): Int {
        val count = koi_token_count(handle, text)
        check(count >= 0) { "llama.cpp could not tokenize" }
        return count
    }

    override fun cpuMask(): List<Int> {
        val out = IntArray(MAX_MASK_CPUS)
        val count = koi_session_cpu_mask(handle, out, out.size)
        return if (count <= 0) emptyList() else out.take(count)
    }

    override fun setCpuMask(cpus: List<Int>) {
        check(koi_session_set_cpu_mask(handle, cpus.toIntArray(), cpus.size) == 0) {
            "llama.cpp could not pin the decode threads to $cpus"
        }
    }


    override fun close() = koi_session_free(handle)
}
