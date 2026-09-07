package io.github.lemcoder.koinference.executorch

import io.github.lemcoder.koinference.executorch.internal.ExecuTorchModel
import io.github.lemcoder.koinference.executorch.internal.ExecuTorchSession
import io.github.lemcoder.koinference.executorch.internal.ExecuTorchSessionOptions
import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.ConnectionGuard
import io.github.lemcoder.koinference.runtime.GeneratingConnection
import io.github.lemcoder.koinference.runtime.KoinferenceException
import io.github.lemcoder.koinference.runtime.generation.GenerationConstraint
import io.github.lemcoder.koinference.runtime.generation.GenerationParameters
import io.github.lemcoder.koinference.runtime.media.ResponsePart
import io.github.lemcoder.koinference.runtime.text.TokenCounting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * One session over a loaded ExecuTorch program.
 *
 * `LlmModule` fixes temperature at load, so [updateGenerationParameters] cannot change it — load a
 * new model instead. The weights belong to [ExecuTorchLoadedModel].
 */
class ExecuTorchGeneratingConnection internal constructor(
    private val model: ExecuTorchModel,
    private val target: String,
    private val sessionOptions: ExecuTorchSessionOptions,
    parameters: GenerationParameters,
) : GeneratingConnection, TokenCounting {

    override var generationParameters: GenerationParameters = parameters
        private set

    private var session: ExecuTorchSession? = null
    private val guard = ConnectionGuard { target }

    override val isClosed: Boolean get() = guard.isClosed

    override suspend fun generate(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
        onPart: (ResponsePart) -> Unit,
    ) {
        val text = flatten(prompt)
        refuse(constraint)
        guard.whileOpen {
            withContext(Dispatchers.Default) {
                session().stream(text).map(ResponsePart::Text).collect { onPart(it) }
            }
        }
    }

    override suspend fun countTokens(text: String): Int = guard.whileOpen {
        session().generatedTokens(text) ?: -1
    }

    override suspend fun updateGenerationParameters(parameters: GenerationParameters) {
        if (parameters.temperature != null && parameters.temperature != generationParameters.temperature) {
            throw KoinferenceException.Unsupported(
                "ExecuTorch fixes temperature when the .pte is loaded; load a new model to change it")
        }
        guard.whileOpen { generationParameters = parameters }
    }

    override suspend fun close() = guard.close { closeSession() }

    private fun session(): ExecuTorchSession =
        session ?: model.openSession(sessionOptions).also { session = it }

    private fun closeSession() {
        session?.close()
        session = null
    }

    private fun flatten(prompt: List<PromptPart>): String = prompt.joinToString("") { part ->
        when (part) {
            is PromptPart.Text -> part.text
            else -> error("ExecuTorch's text programs take text; got ${part::class.simpleName}")
        }
    }

    private fun refuse(constraint: GenerationConstraint?) {
        if (constraint != null) error("ExecuTorch's Android binding exposes no constrained decoding")
    }
}
