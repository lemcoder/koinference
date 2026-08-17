@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.llamacpp.internal

import cnames.structs.KoiModel
import cnames.structs.KoiSession
import koinference.KoiSessionParams
import koinference.koi_backend_free
import koinference.koi_backend_init
import koinference.koi_embed
import koinference.koi_generate
import koinference.koi_generate_begin
import koinference.koi_generate_end
import koinference.koi_generate_next
import koinference.koi_json_schema_to_grammar
import koinference.koi_model_free
import koinference.koi_model_load
import koinference.koi_session_create
import koinference.koi_session_free
import koinference.koi_system_info
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cValue
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString

internal actual fun llamaBackendInit() = koi_backend_init()
internal actual fun llamaBackendFree() = koi_backend_free()
internal actual fun llamaSystemInfo(): String = koi_system_info()?.toKString() ?: ""

internal actual fun llamaModelLoad(path: String, nGpuLayers: Int): Long =
    koi_model_load(path, nGpuLayers)?.rawValue?.toLong() ?: 0L

internal actual fun llamaModelFree(handle: Long) {
    if (handle != 0L) koi_model_free(handle.toCPointer<KoiModel>())
}

internal actual fun llamaSessionCreate(
    modelHandle: Long,
    nCtx: Int,
    nThreads: Int,
    nPredict: Int,
    temp: Float,
    topK: Int,
    minP: Float,
): Long {
    if (modelHandle == 0L) return 0L
    // koi_default_session_params() hands back a CValue — an immutable off-heap snapshot — so the
    // fields cannot be assigned through it. Every field of the struct is set here anyway, the same
    // way the JVM actual packs them, so build the value directly.
    val params = cValue<KoiSessionParams> {
        n_ctx = nCtx
        n_threads = nThreads
        n_predict = nPredict
        this.temp = temp // qualified: unqualified `temp` is this function's parameter, not the field
        top_k = topK
        min_p = minP
    }
    return koi_session_create(modelHandle.toCPointer<KoiModel>(), params)
        ?.rawValue?.toLong() ?: 0L
}

internal actual fun llamaSessionFree(handle: Long) {
    if (handle != 0L) koi_session_free(handle.toCPointer<KoiSession>())
}

internal actual fun llamaGenerate(
    sessionHandle: Long,
    systemPrompt: String?,
    userPrompt: String,
    grammar: String?,
): String {
    if (sessionHandle == 0L) return ""
    val bufSize = 1 shl 20 // 1 MiB
    return memScoped {
        val buf = allocArray<kotlinx.cinterop.ByteVar>(bufSize)
        val len = koi_generate(
            sessionHandle.toCPointer<KoiSession>(),
            systemPrompt,
            userPrompt,
            grammar,
            buf,
            bufSize,
        )
        if (len >= 0) buf.toKString() else ""
    }
}

internal actual fun llamaJsonSchemaToGrammar(schema: String): String {
    val bufSize = 1 shl 20 // 1 MiB: a grammar from a nested schema outgrows anything smaller
    return memScoped {
        val buf = allocArray<kotlinx.cinterop.ByteVar>(bufSize)
        val len = koi_json_schema_to_grammar(schema, buf, bufSize)
        if (len > 0) buf.toKString() else ""
    }
}

internal actual fun llamaGenerateBegin(
    sessionHandle: Long,
    systemPrompt: String?,
    userPrompt: String,
    grammar: String?,
): Int {
    if (sessionHandle == 0L) return -1
    return koi_generate_begin(sessionHandle.toCPointer<KoiSession>(), systemPrompt, userPrompt, grammar)
}

internal actual fun llamaGenerateNext(sessionHandle: Long): String? {
    if (sessionHandle == 0L) return null
    // One token per call, and a token is at most a handful of bytes; the facade reports an
    // error rather than truncating, so a fixed buffer is safe here.
    val bufSize = 512
    return memScoped {
        val buf = allocArray<kotlinx.cinterop.ByteVar>(bufSize)
        val written = koi_generate_next(sessionHandle.toCPointer<KoiSession>(), buf, bufSize)
        check(written >= 0) { "llama.cpp streaming failed" }
        if (written == 0) null else buf.toKString()
    }
}

internal actual fun llamaGenerateEnd(sessionHandle: Long) {
    if (sessionHandle != 0L) koi_generate_end(sessionHandle.toCPointer<KoiSession>())
}

internal actual fun llamaEmbed(sessionHandle: Long, text: String): FloatArray {
    if (sessionHandle == 0L) return FloatArray(0)
    val maxDims = 8192
    return memScoped {
        val buf = allocArray<kotlinx.cinterop.FloatVar>(maxDims)
        val dims = koi_embed(sessionHandle.toCPointer<KoiSession>(), text, buf, maxDims)
        if (dims <= 0) FloatArray(0)
        else FloatArray(dims) { buf[it] }
    }
}
