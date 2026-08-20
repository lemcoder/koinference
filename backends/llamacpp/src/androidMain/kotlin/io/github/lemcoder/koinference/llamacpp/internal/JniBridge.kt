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

internal object JniBridge : LlamaCppBridge {

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
