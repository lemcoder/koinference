package io.github.lemcoder.koinference.benchmark.app

import android.os.SystemClock
import io.github.lemcoder.koinference.Koinference
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.litertlm.LiteRtLm
import io.github.lemcoder.koinference.llamacpp.LlamaCpp
import io.github.lemcoder.koinference.runtime.Accelerator
import io.github.lemcoder.koinference.runtime.GeneratingRuntime
import io.github.lemcoder.koinference.runtime.GenerationConstraint
import io.github.lemcoder.koinference.runtime.GenerationParameters
import io.github.lemcoder.koinference.runtime.ResponsePart
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

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
    private val koi: Koinference,
    private val runtime: GeneratingRuntime,
) {

    /** The id clients see in `/v1/models` and send back as `model`. */
    val modelId: String = File(modelPath).nameWithoutExtension

    /**
     * The reply, as text, for an endpoint whose wire format only carries text.
     *
     * A reply is a stream of [ResponsePart] because some models interleave text with audio. This
     * is a chat-completions server: anything that is not text has nowhere to go in an SSE `delta`,
     * so it is dropped here, where a reader can see it happening, rather than by a convenience
     * on the runtime.
     */
    fun stream(prompt: String, schema: String?): Flow<String> =
        runtime.streamResponse(prompt, schema?.let { GenerationConstraint.JsonSchema(it) })
            .mapNotNull { part -> (part as? ResponsePart.Text)?.text }

    suspend fun unload() = koi.unload(modelPath)

    companion object {

        /** The engines this app links. Adding one is adding it here. */
        private val backends = listOf(LlamaCpp, LiteRtLm)

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

            // Which engine reads this container is Koinference's answer, not a branch here.
            val koi = Koinference(
                backends = backends,
                config = ModelConfig(
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
            val engineId = koi.backendFor(modelPath)?.id
                ?: error("No engine for $modelPath. Registered: ${koi.backendIds}")

            val start = SystemClock.elapsedRealtimeNanos()
            val runtime = koi.load(modelPath)
            val loadMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0

            return LoadedModel(engineId, modelPath, loadMs, koi, runtime)
        }

    }
}
