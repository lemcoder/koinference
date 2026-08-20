@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.llamacpp.internal

import cnames.structs.KoiModel
import cnames.structs.KoiSession
import io.github.lemcoder.koinference.Accelerator
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

internal class FacadeSession(private val handle: CPointer<KoiSession>) : LlamaCppSession {

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

    override fun cpuMask(): List<Int> = memScoped {
        val buffer = allocArray<IntVar>(MAX_MASK_CPUS)
        val count = koi_session_cpu_mask(handle, buffer, MAX_MASK_CPUS)
        if (count <= 0) emptyList() else List(count) { buffer[it] }
    }

    override fun setCpuMask(cpus: List<Int>) = memScoped {
        val requested = allocArray<IntVar>(maxOf(cpus.size, 1))
        cpus.forEachIndexed { index, cpu -> requested[index] = cpu }
        check(koi_session_set_cpu_mask(handle, requested, cpus.size) == 0) {
            "llama.cpp could not pin the decode threads to $cpus"
        }
    }

    override fun close() {
        koi_session_free(handle)
    }
}
