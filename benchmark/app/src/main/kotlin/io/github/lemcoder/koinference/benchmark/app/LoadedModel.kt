package io.github.lemcoder.koinference.benchmark.app

import android.os.SystemClock
import io.github.lemcoder.koinference.GenerationConstraint
import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.ModelLoader
import io.github.lemcoder.koinference.StreamingTextRuntime
import io.github.lemcoder.koinference.litertlm.LiteRtLmModelLoader
import io.github.lemcoder.koinference.llamacpp.LlamaCppModelLoader
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * One model, loaded, with the engine that loaded it.
 *
 * The whole integration with the library is this file, and that is the point: pick a loader by
 * file extension, call `load`, call `streamResponse`. The server does not know what llama.cpp or
 * LiteRT-LM are, and adding a third engine means adding a branch to [loaderFor].
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
        ): LoadedModel {
            require(File(modelPath).isFile) { "No model file at $modelPath" }

            val (engineId, loader) = loaderFor(
                modelPath = modelPath,
                maxNewTokens = maxNewTokens,
                parameters = parameters,
                threads = threads,
                contextTokens = contextTokens,
                useGpu = useGpu,
            )

            val start = SystemClock.elapsedRealtimeNanos()
            val runtime = loader.load(modelPath) as StreamingTextRuntime
            val loadMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0

            return LoadedModel(engineId, modelPath, loadMs, loader, runtime)
        }

        private fun loaderFor(
            modelPath: String,
            maxNewTokens: Int,
            parameters: GenerationParameters,
            threads: Int,
            contextTokens: Int,
            useGpu: Boolean,
        ): Pair<String, ModelLoader> = when {
            modelPath.endsWith(".gguf") -> "llama.cpp" to LlamaCppModelLoader(
                settings = runtimeSettings(useGpu),
                nCtx = contextTokens,
                nThreads = threads,
                nPredict = maxNewTokens,
            )

            modelPath.endsWith(".litertlm") || modelPath.endsWith(".task") ->
                "litert-lm" to LiteRtLmModelLoader(
                    settings = runtimeSettings(useGpu),
                    parameters = parameters,
                    nThreads = threads,
                    maxTokens = contextTokens,
                    maxOutputTokens = maxNewTokens,
                )

            else -> throw IllegalArgumentException(
                "No engine for $modelPath — expected .gguf, .litertlm or .task",
            )
        }

        private fun runtimeSettings(useGpu: Boolean) =
            io.github.lemcoder.koinference.RuntimeSettings(
                backend = if (useGpu) {
                    io.github.lemcoder.koinference.InferenceBackend.GPU
                } else {
                    io.github.lemcoder.koinference.InferenceBackend.CPU
                },
            )
    }
}
