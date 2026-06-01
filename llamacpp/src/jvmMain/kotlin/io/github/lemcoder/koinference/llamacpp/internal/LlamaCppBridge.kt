package io.github.lemcoder.koinference.llamacpp.internal

// JNI bindings — compiled from cpp/jni/koinference_jni.cpp.
// Build with: cmake -DBUILD_JNI=ON and place the output .so/.dylib/.dll
// on java.library.path before the JVM starts.
internal object LlamaCppBridgeJni {
    init {
        System.loadLibrary("koinference-jni")
    }

    external fun backendInit()
    external fun backendFree()
    external fun systemInfo(): String

    external fun modelLoad(path: String): Long
    external fun modelFree(handle: Long)

    external fun sessionCreate(
        modelHandle: Long,
        nCtx: Int,
        nThreads: Int,
        nPredict: Int,
        temp: Float,
        topK: Int,
        minP: Float,
    ): Long

    external fun sessionFree(handle: Long)

    external fun generate(
        sessionHandle: Long,
        systemPrompt: String?,
        userPrompt: String,
        grammar: String?,
    ): String

    external fun embed(sessionHandle: Long, text: String): FloatArray
}

internal actual fun llamaBackendInit()                        = LlamaCppBridgeJni.backendInit()
internal actual fun llamaBackendFree()                        = LlamaCppBridgeJni.backendFree()
internal actual fun llamaSystemInfo(): String                 = LlamaCppBridgeJni.systemInfo()
internal actual fun llamaModelLoad(path: String): Long        = LlamaCppBridgeJni.modelLoad(path)
internal actual fun llamaModelFree(handle: Long)              = LlamaCppBridgeJni.modelFree(handle)

internal actual fun llamaSessionCreate(
    modelHandle: Long,
    nCtx: Int,
    nThreads: Int,
    nPredict: Int,
    temp: Float,
    topK: Int,
    minP: Float,
): Long = LlamaCppBridgeJni.sessionCreate(modelHandle, nCtx, nThreads, nPredict, temp, topK, minP)

internal actual fun llamaSessionFree(handle: Long)            = LlamaCppBridgeJni.sessionFree(handle)

internal actual fun llamaGenerate(
    sessionHandle: Long,
    systemPrompt: String?,
    userPrompt: String,
    grammar: String?,
): String = LlamaCppBridgeJni.generate(sessionHandle, systemPrompt, userPrompt, grammar)

internal actual fun llamaEmbed(sessionHandle: Long, text: String): FloatArray =
    LlamaCppBridgeJni.embed(sessionHandle, text)
