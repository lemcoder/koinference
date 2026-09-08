package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.runtime.Connection
import io.github.lemcoder.koinference.runtime.Model
import io.github.lemcoder.koinference.runtime.generation.GenerationParameters
import io.github.lemcoder.koinference.llamacpp.gguf.GgufMetadata
import io.github.lemcoder.koinference.llamacpp.gguf.GgufParser
import io.github.lemcoder.koinference.llamacpp.gguf.readFileBytes
import io.github.lemcoder.koinference.llamacpp.internal.CpuPlacementSource
import io.github.lemcoder.koinference.llamacpp.internal.LlamaCppBridge
import io.github.lemcoder.koinference.llamacpp.internal.LlamaCppModel
import io.github.lemcoder.koinference.llamacpp.internal.ModelOptions
import io.github.lemcoder.koinference.llamacpp.internal.platformCpuPlacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A loaded GGUF model: the weights, and a factory for connections over them.
 *
 * The weights are loaded by the time an instance exists; each [open] makes a fresh session, so
 * connections do not share decode state. Where the model runs (CPU/GPU) was fixed at load — to move
 * it, load a second model.
 */
class LlamaCppLoadedModel internal constructor(
    private val bridge: LlamaCppBridge,
    private val modelOptions: ModelOptions,
    private val systemPrompt: String?,
    private val model: LlamaCppModel,
    private val nCtx: Int,
    private val nThreads: Int,
    private val nPredict: Int,
    private val parameters: GenerationParameters,
    private val placementPolicy: CpuPlacementSource = platformCpuPlacement(),
) : Model {

    private var closed = false

    override suspend fun open(): Connection = LlamaCppGeneratingConnection(
        bridge = bridge,
        model = model,
        target = modelOptions.modelPath,
        systemPrompt = systemPrompt,
        nCtx = nCtx,
        nThreads = nThreads,
        nPredict = nPredict,
        parameters = parameters,
        placementPolicy = placementPolicy,
    )

    /** Idempotent: the loader and [Koinference.unloadModel] can both reach here. */
    override suspend fun close() {
        if (closed) return
        closed = true
        withContext(Dispatchers.Default) { model.close() }
    }

    suspend fun readGgufMetadata(): GgufMetadata =
        GgufParser.parse(readFileBytes(modelOptions.modelPath))
}
