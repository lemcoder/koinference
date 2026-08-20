package io.github.lemcoder.koinference.llamacpp.internal

import io.github.lemcoder.koinference.runtime.Accelerator
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

internal class JniModel(private val handle: Long) : LlamaCppModel {

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
