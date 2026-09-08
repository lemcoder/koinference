package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.GeneratingConnection
import io.github.lemcoder.koinference.runtime.KoinferenceException
import io.github.lemcoder.koinference.runtime.generation.GenerationConstraint
import io.github.lemcoder.koinference.runtime.generation.GenerationParameters
import io.github.lemcoder.koinference.runtime.media.ResponsePart

/** A generating connection whose reply is fixed by the model that opened it. */
internal class FakeGeneratingConnection(
    private val target: String,
    parameters: GenerationParameters,
    private val reply: () -> List<ResponsePart>,
) : GeneratingConnection {

    private var closed = false

    override val isClosed: Boolean get() = closed

    override var generationParameters: GenerationParameters = parameters
        private set

    override suspend fun generate(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
        onPart: (ResponsePart) -> Unit,
    ) {
        if (closed) throw KoinferenceException.ConnectionClosed(target)
        reply().forEach(onPart)
    }

    override suspend fun updateGenerationParameters(parameters: GenerationParameters) {
        generationParameters = parameters
    }

    override suspend fun close() { closed = true }
}
