package io.github.lemcoder.koinference.litertlm.internal

import io.github.lemcoder.koinference.InferenceBackend
import io.github.lemcoder.koinference.litertlm.jni.kniBridge0
import io.github.lemcoder.koinference.litertlm.jni.kniBridge1
import io.github.lemcoder.koinference.litertlm.jni.kniBridge10
import io.github.lemcoder.koinference.litertlm.jni.kniBridge2
import io.github.lemcoder.koinference.litertlm.jni.kniBridge4
import io.github.lemcoder.koinference.litertlm.jni.kniBridge5
import io.github.lemcoder.koinference.litertlm.jni.kniBridge6
import io.github.lemcoder.koinference.litertlm.jni.kniBridge7
import io.github.lemcoder.koinference.litertlm.jni.kniBridge8
import io.github.lemcoder.koinference.litertlm.jni.kniBridge9
import io.github.lemcoder.koinference.litertlm.jni.kniCString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Android binds the same C facade the Apple leg does, through bridges the Konan plugin generates
// from koinference_litertlm_facade.h. It used to go through Google's Kotlin API, because the
// Maven AAR's liblitertlm_jni.so exports only its own Java_* entry points and no litert_lm_*
// symbols — there was nothing for a facade to link against. The C API archive published at
// 0.16.0 exports 144 of them, so the two legs now differ only in how they reach the same
// functions: cinterop there, JNI here.
//
// Losing the Kotlin API also loses a coupling worth being rid of: its Flow overload of
// sendMessageAsync called a kotlinx-coroutines synthetic that 1.10.x no longer has, and died
// with NoSuchMethodError on device. A C API cannot drift with a Kotlin library's version.

/** Layout of KoiLmSessionParams: six 4-byte fields, no padding. */
private const val SESSION_PARAMS_SIZE = 24

/** One chunk is a token or a few; the facade errors rather than truncating past this. */
private const val CHUNK_BYTES = 512

/** First guess at a reply's size; a longer one is collected with koilm_last_response. */
private const val INITIAL_REPLY_BYTES = 8192

internal actual fun platformBridge(): LiteRtLmBridge = JniBridge

private fun lastError(): String = kniCString(kniBridge0()).orEmpty()

private object JniBridge : LiteRtLmBridge {
    override fun openEngine(options: EngineOptions): LiteRtLmEngine {
        val handle = kniBridge1(
            options.modelPath,
            options.cacheDir,
            when (options.backend) {
                // The cinterop leg imports these from the generated bindings; the JNI leg has no
                // such bindings, so BackendId mirrors the header. BackendIdTest fails if they drift.
                InferenceBackend.GPU -> BackendId.GPU
                InferenceBackend.CPU -> BackendId.CPU
            },
            options.nThreads,
            options.maxTokens,
        )
        check(handle != 0L) { "LiteRT-LM could not load ${options.modelPath}: ${lastError()}" }
        return JniEngine(handle)
    }
}

private class JniEngine(private val handle: Long) : LiteRtLmEngine {

    override fun openConversation(options: ConversationOptions): LiteRtLmConversation {
        // Packed by hand for the same reason the llama.cpp bridge does it: the generator marshals
        // a by-value struct as a byte array, so the field order in the header is the contract.
        val params = ByteBuffer.allocate(SESSION_PARAMS_SIZE).order(ByteOrder.nativeOrder())
            .putInt(options.maxTokens)
            .putInt(options.topK)
            .putFloat(options.topP)
            .putFloat(options.temperature)
            .putInt(options.seed ?: UNSEEDED)
            .putInt(if (options.greedy) 1 else 0)

        val conversation = kniBridge4(handle, params.array(), options.systemPrompt)
        check(conversation != 0L) { "LiteRT-LM could not open a conversation: ${lastError()}" }
        return JniConversation(conversation)
    }

    override fun close() = kniBridge2(handle)
}

private class JniConversation(private val handle: Long) : LiteRtLmConversation {

    override fun generate(prompt: String, jsonSchema: String?): String {
        val buffer = ByteArray(INITIAL_REPLY_BYTES)
        val needed = kniBridge6(handle, prompt, jsonSchema, buffer, buffer.size)
        check(needed >= 0) { "LiteRT-LM generation failed: ${lastError()}" }

        // needed is what the reply wants, not what was written. A reply longer than the first
        // guess is still held by the facade, so it is collected rather than regenerated —
        // generating again would add a second user turn to the conversation.
        val raw = if (needed < buffer.size) {
            String(buffer, 0, needed, Charsets.UTF_8)
        } else {
            val grown = ByteArray(needed + 1)
            val collected = kniBridge7(grown, grown.size)
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
        check(kniBridge8(handle, prompt, jsonSchema) == 0) {
            "LiteRT-LM could not start streaming: ${lastError()}"
        }
        try {
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                val written = withContext(Dispatchers.IO) { kniBridge9(handle, buffer, buffer.size) }
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
            kniBridge10(handle)
        }
    }

    override fun close() = kniBridge5(handle)
}
