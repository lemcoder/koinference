@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.llamacpp.internal

import cnames.structs.KoiModel
import cnames.structs.KoiSession
import koinference.koi_backend_free
import koinference.koi_backend_init
import koinference.koi_default_session_params
import koinference.koi_embed
import koinference.koi_generate
import koinference.koi_model_free
import koinference.koi_model_load
import koinference.koi_session_create
import koinference.koi_session_free
import koinference.koi_system_info
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString

internal actual fun llamaBackendInit() = koi_backend_init()
internal actual fun llamaBackendFree() = koi_backend_free()
internal actual fun llamaSystemInfo(): String = koi_system_info()?.toKString() ?: ""

internal actual fun llamaModelLoad(path: String): Long =
    koi_model_load(path)?.rawValue?.toLong() ?: 0L

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
    val params = koi_default_session_params().apply {
        this.n_ctx     = nCtx
        this.n_threads = nThreads
        this.n_predict = nPredict
        this.temp      = temp
        this.top_k     = topK
        this.min_p     = minP
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
