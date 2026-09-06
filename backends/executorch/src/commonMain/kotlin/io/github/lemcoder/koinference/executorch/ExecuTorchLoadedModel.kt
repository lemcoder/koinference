package io.github.lemcoder.koinference.executorch

import io.github.lemcoder.koinference.executorch.internal.ExecuTorchModel
import io.github.lemcoder.koinference.executorch.internal.ExecuTorchModelOptions
import io.github.lemcoder.koinference.executorch.internal.ExecuTorchSessionOptions
import io.github.lemcoder.koinference.runtime.Connection
import io.github.lemcoder.koinference.runtime.Model
import io.github.lemcoder.koinference.runtime.generation.GenerationParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A loaded ExecuTorch program: the weights, and a factory for sessions over them. */
class ExecuTorchLoadedModel internal constructor(
    private val modelOptions: ExecuTorchModelOptions,
    private val model: ExecuTorchModel,
    private val sessionOptions: ExecuTorchSessionOptions,
    private val parameters: GenerationParameters,
) : Model {

    private var closed = false

    override suspend fun open(): Connection =
        ExecuTorchGeneratingConnection(model, modelOptions.modelPath, sessionOptions, parameters)

    override suspend fun close() {
        if (closed) return
        closed = true
        withContext(Dispatchers.Default) { model.close() }
    }
}
