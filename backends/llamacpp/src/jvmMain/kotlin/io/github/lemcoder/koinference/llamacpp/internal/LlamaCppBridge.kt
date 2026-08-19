package io.github.lemcoder.koinference.llamacpp.internal

import io.github.lemcoder.koinference.llamacpp.jni.kniBridge0
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge1
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge2
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge3
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge4
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge6
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge7
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge8
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge9
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge10
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge11
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge12
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge13
import io.github.lemcoder.koinference.llamacpp.jni.kniBridge14
import io.github.lemcoder.koinference.llamacpp.jni.kniCString
import java.nio.ByteBuffer
import java.nio.ByteOrder

// The kniBridgeN functions are generated from cpp/facade/koinference_facade.h by the Konan plugin's
// `generateJvmInterop` task; the stub library they load is produced by `linkJvmInterop`. The bridge
// numbering follows the header's declaration order — see the generated file's `/** C: … */` comments.

private const val GEN_BUF_SIZE = 1 shl 20 // 1 MiB generation output buffer
private const val MAX_EMBED_DIMS = 8192
private const val GRAMMAR_BUF_SIZE = 1 shl 20 // 1 MiB: a nested schema outgrows anything smaller

/** Layout of `KoiSessionParams`: six 4-byte fields, no padding. */
private const val SESSION_PARAMS_SIZE = 24

internal actual fun llamaBackendInit() = kniBridge0()
internal actual fun llamaBackendFree() = kniBridge1()
internal actual fun llamaSystemInfo(): String = kniCString(kniBridge2()).orEmpty()

internal actual fun llamaModelLoad(path: String, nGpuLayers: Int): Long = kniBridge3(path, nGpuLayers)
internal actual fun llamaModelFree(handle: Long) = kniBridge4(handle)

internal actual fun llamaSessionCreate(
    modelHandle: Long,
    nCtx: Int,
    nThreads: Int,
    nPredict: Int,
    temp: Float,
    topK: Int,
    minP: Float,
): Long {
    val params = ByteBuffer.allocate(SESSION_PARAMS_SIZE).order(ByteOrder.nativeOrder())
        .putInt(nCtx)
        .putInt(nThreads)
        .putInt(nPredict)
        .putFloat(temp)
        .putInt(topK)
        .putFloat(minP)
    return kniBridge6(modelHandle, params.array())
}

internal actual fun llamaSessionFree(handle: Long) = kniBridge7(handle)

internal actual fun llamaGenerate(
    sessionHandle: Long,
    systemPrompt: String?,
    userPrompt: String,
    grammar: String?,
): String {
    val out = ByteArray(GEN_BUF_SIZE)
    val written = kniBridge8(sessionHandle, systemPrompt, userPrompt, grammar, out, out.size)
    return if (written < 0) "" else String(out, 0, written, Charsets.UTF_8)
}

internal actual fun llamaEmbed(sessionHandle: Long, text: String): FloatArray {
    val out = FloatArray(MAX_EMBED_DIMS)
    val dims = kniBridge9(sessionHandle, text, out, out.size)
    return if (dims <= 0) FloatArray(0) else out.copyOf(dims)
}

internal actual fun llamaJsonSchemaToGrammar(schema: String): String {
    val out = ByteArray(GRAMMAR_BUF_SIZE)
    val written = kniBridge10(schema, out, out.size)
    return if (written <= 0) "" else String(out, 0, written, Charsets.UTF_8)
}

// Bridges 11-13 are the streaming trio, in header order.
private const val CHUNK_BUF_SIZE = 512

internal actual fun llamaGenerateBegin(
    sessionHandle: Long,
    systemPrompt: String?,
    userPrompt: String,
    grammar: String?,
): Int = if (sessionHandle == 0L) -1 else kniBridge11(sessionHandle, systemPrompt, userPrompt, grammar)

internal actual fun llamaGenerateNext(sessionHandle: Long): String? {
    if (sessionHandle == 0L) return null
    val out = ByteArray(CHUNK_BUF_SIZE)
    val written = kniBridge12(sessionHandle, out, out.size)
    check(written >= 0) { "llama.cpp streaming failed" }
    return if (written == 0) null else String(out, 0, written, Charsets.UTF_8)
}

internal actual fun llamaGenerateEnd(sessionHandle: Long) {
    if (sessionHandle != 0L) kniBridge13(sessionHandle)
}

// Bridge 14 is koi_token_count, appended after the streaming trio.
internal actual fun llamaTokenCount(sessionHandle: Long, text: String): Int =
    if (sessionHandle == 0L) -1 else kniBridge14(sessionHandle, text)
