package io.github.lemcoder.koinference.benchmark.app

import android.os.SystemClock
import io.github.lemcoder.koinference.runtime.Accelerator
import io.github.lemcoder.koinference.backend.BackendRegistry
import io.github.lemcoder.koinference.runtime.GenerationConstraint
import io.github.lemcoder.koinference.runtime.GenerationParameters
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import io.github.lemcoder.koinference.runtime.StreamingTextRuntime
import io.github.lemcoder.koinference.litertlm.LiteRtLm
import io.github.lemcoder.koinference.llamacpp.LlamaCpp
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * One model, loaded, with the engine that loaded it.
 *
 * The whole integration with the library is this file, and that is the point: let the registry
 * pick the backend for the model, call `load`, call `streamResponse`. The server does not know
 * what llama.cpp or LiteRT-LM are, and adding a third engine means adding it to [backends].
 */
class LoadedModel private constructor(
    val engineId: String,
    val modelPath: String,
    val modelLoadMs: Double,
    private val loader: ModelLoader,
    private val runtime: StreamingTextRuntime,
) {

    /** The id clients see in `/v1/models` and send back as `model`. */
    val modelId: String = File(modelPath).nameWithoutExtension

    fun stream(prompt: String, schema: String?): Flow<String> =
        runtime.streamResponse(prompt, schema?.let { GenerationConstraint.JsonSchema(it) })

    suspend fun unload() = loader.unload(modelPath)

    companion object {

        /** The engines this app links. Adding one is adding it here. */
        val backends = BackendRegistry(LlamaCpp, LiteRtLm)

        /**
         * @param maxNewTokens fixed for the life of the model, because both backends decide it
         *        when the model is loaded rather than per request. A request asking for a
         *        different limit is answered with this one, and the response says so.
         */
        suspend fun load(
            modelPath: String,
            maxNewTokens: Int,
            parameters: GenerationParameters,
            threads: Int,
            contextTokens: Int,
            useGpu: Boolean,
            cacheDir: String?,
        ): LoadedModel {
            require(File(modelPath).isFile) { "No model file at $modelPath" }

            // Which engine reads this container is the backend's own answer, not a branch here.
            val backend = backends.requireForModel(modelPath)
            val loader = backend.loader(
                ModelConfig(
                    settings = RuntimeSettings(
                        accelerator = if (useGpu) Accelerator.GPU else Accelerator.CPU,
                    ),
                    parameters = parameters,
                    contextTokens = contextTokens,
                    maxOutputTokens = maxNewTokens,
                    threads = threads,
                    cacheDir = cacheDir,
                ),
            )

            val start = SystemClock.elapsedRealtimeNanos()
            val runtime = loader.load(modelPath) as StreamingTextRuntime
            val loadMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0

            return LoadedModel(backend.id, modelPath, loadMs, loader, runtime)
        }

    }
}
