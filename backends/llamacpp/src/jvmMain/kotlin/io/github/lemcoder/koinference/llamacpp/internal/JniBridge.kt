package io.github.lemcoder.koinference.llamacpp.internal

import io.github.lemcoder.koinference.Accelerator
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge0
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge3
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge4
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge6
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge7
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge8
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge10
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge11
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge12
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge13
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge14
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge15
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge16
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

// The kniBridgeN functions are generated from native/facade/koinference_facade.h by the Konan
// plugin's `generateJvmInterop` task, numbered by the header's declaration order — see the
// generated file's `/** C: … */` comments. Index 5 is koi_default_session_params, which returns a
// struct by value and gets no bridge; index 9 is koi_embed, which nothing calls (see
// docs/backends.md on why it stays in the header).
//
// This file is duplicated verbatim in androidMain. That is deliberate — see docs/backends.md.

/** 1 MiB. A grammar from a nested schema outgrows anything smaller, and so does a long reply. */
private const val LARGE_BUFFER_BYTES = 1 shl 20

/** One chunk is a single token; the facade errors rather than truncating past this. */
private const val CHUNK_BYTES = 512

/** More cores than any phone has, so the mask is never truncated on the way out. */
private const val MAX_MASK_CPUS = 64

/** Layout of `KoiSessionParams`: six 4-byte fields, no padding. */
private const val SESSION_PARAMS_SIZE = 24

internal actual fun platformBridge(): LlamaCppBridge = JniBridge

private object JniBridge : LlamaCppBridge {

    // See FacadeBridge for why this is initialised once and never freed.
    private val backendInit: Unit by lazy { kniBridge0() }

    override fun openModel(options: ModelOptions): LlamaCppModel {
        backendInit
        val handle = kniBridge3(
            options.modelPath,
            when (options.accelerator) {
                Accelerator.CPU -> 0
                Accelerator.GPU -> ALL_GPU_LAYERS
            },
        )
        check(handle != 0L) { "llama.cpp could not load ${options.modelPath}" }
        return JniModel(handle)
    }

    override fun jsonSchemaToGrammar(schema: String): String {
        val out = ByteArray(LARGE_BUFFER_BYTES)
        val written = kniBridge10(schema, out, out.size)
        require(written > 0) { "Not a convertible JSON schema: $schema" }
        return String(out, 0, written, Charsets.UTF_8)
    }
}

private class JniModel(private val handle: Long) : LlamaCppModel {

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

        val session = kniBridge6(handle, params.array())
        check(session != 0L) { "llama.cpp could not create a session" }
        return JniSession(session)
    }

    override fun close() = kniBridge4(handle)
}

private class JniSession(private val handle: Long) : LlamaCppSession {

    override fun generate(systemPrompt: String?, prompt: String, grammar: String?): String {
        val out = ByteArray(LARGE_BUFFER_BYTES)
        val written = kniBridge8(handle, systemPrompt, prompt, grammar, out, out.size)
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
            check(kniBridge11(handle, systemPrompt, prompt, grammar) >= 0) {
                "llama.cpp could not start generating"
            }
            try {
                val buffer = ByteArray(CHUNK_BYTES)
                while (true) {
                    // withContext per token rather than around the loop: emitting has to happen in
                    // the flow's own context, and the decode is what belongs on Default.
                    val written = withContext(Dispatchers.Default) {
                        kniBridge12(handle, buffer, buffer.size)
                    }
                    check(written >= 0) { "llama.cpp streaming failed" }
                    if (written == 0) break
                    emit(String(buffer, 0, written, Charsets.UTF_8))
                }
            } finally {
                kniBridge13(handle)
            }
        }

    override fun tokenCount(text: String): Int {
        val count = kniBridge14(handle, text)
        check(count >= 0) { "llama.cpp could not tokenize" }
        return count
    }

    override fun cpuMask(): List<Int> {
        val out = IntArray(MAX_MASK_CPUS)
        val count = kniBridge15(handle, out, out.size)
        return if (count <= 0) emptyList() else out.take(count)
    }

    override fun setCpuMask(cpus: List<Int>) {
        check(kniBridge16(handle, cpus.toIntArray(), cpus.size) == 0) {
            "llama.cpp could not pin the decode threads to $cpus"
        }
    }


    override fun close() = kniBridge7(handle)
}
