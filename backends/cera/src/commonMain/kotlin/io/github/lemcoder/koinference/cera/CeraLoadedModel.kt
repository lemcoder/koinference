package io.github.lemcoder.koinference.cera

import io.github.lemcoder.koinference.cera.internal.CeraModel
import io.github.lemcoder.koinference.cera.internal.CeraModelOptions
import io.github.lemcoder.koinference.cera.internal.CeraSessionOptions
import io.github.lemcoder.koinference.runtime.Connection
import io.github.lemcoder.koinference.runtime.Model
import io.github.lemcoder.koinference.runtime.generation.GenerationParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A loaded Cera model: the weights, and a factory for sessions over them. */
class CeraLoadedModel internal constructor(
    private val modelOptions: CeraModelOptions,
    private val model: CeraModel,
    private val sessionOptions: CeraSessionOptions,
    private val parameters: GenerationParameters,
) : Model {

    private var closed = false

    override suspend fun open(): Connection =
        CeraGeneratingConnection(model, modelOptions.modelPath, sessionOptions, parameters)

    override suspend fun close() {
        if (closed) return
        closed = true
        withContext(Dispatchers.Default) { model.close() }
    }
}
