package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.runtime.Connection
import io.github.lemcoder.koinference.runtime.Model
import io.github.lemcoder.koinference.runtime.generation.GenerationParameters
import io.github.lemcoder.koinference.litertlm.internal.EngineOptions
import io.github.lemcoder.koinference.litertlm.internal.LiteRtLmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A loaded LiteRT-LM engine: the weights, and a factory for conversations over them. */
class LiteRtLmLoadedModel internal constructor(
    private val engineOptions: EngineOptions,
    private val systemPrompt: String?,
    private val engine: LiteRtLmEngine,
    private val parameters: GenerationParameters,
    private val maxOutputTokens: Int,
) : Model {

    private var closed = false

    override suspend fun open(): Connection = LiteRtLmGeneratingConnection(
        engine = engine,
        target = engineOptions.modelPath,
        systemPrompt = systemPrompt,
        maxOutputTokens = maxOutputTokens,
        parameters = parameters,
    )

    override suspend fun close() {
        if (closed) return
        closed = true
        withContext(Dispatchers.Default) { engine.close() }
    }
}
