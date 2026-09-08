package io.github.lemcoder.koinference.cera

import io.github.lemcoder.koinference.cera.internal.CeraModel
import io.github.lemcoder.koinference.cera.internal.CeraSession
import io.github.lemcoder.koinference.cera.internal.CeraSessionOptions
import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.ConnectionGuard
import io.github.lemcoder.koinference.runtime.GeneratingConnection
import io.github.lemcoder.koinference.runtime.generation.GenerationConstraint
import io.github.lemcoder.koinference.runtime.generation.GenerationParameters
import io.github.lemcoder.koinference.runtime.media.ResponsePart
import io.github.lemcoder.koinference.runtime.text.TokenCounting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** One session over a loaded Cera model. Cera accumulates, so the session is reset each turn. */
class CeraGeneratingConnection internal constructor(
    private val model: CeraModel,
    private val target: String,
    sessionOptions: CeraSessionOptions,
    parameters: GenerationParameters,
) : GeneratingConnection, TokenCounting {

    override var generationParameters: GenerationParameters = parameters
        private set

    private var sessionOptions: CeraSessionOptions = sessionOptions
    private var session: CeraSession? = null
    private val guard = ConnectionGuard { target }

    override val isClosed: Boolean get() = guard.isClosed

    override suspend fun generate(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
        onPart: (ResponsePart) -> Unit,
    ) {
        val text = flatten(prompt)
        guard.whileOpen {
            withContext(Dispatchers.Default) {
                val grammar = grammarFor(constraint)
                freshSession().stream(text, grammar).map(ResponsePart::Text).collect { onPart(it) }
            }
        }
    }

    override suspend fun countTokens(text: String): Int = guard.whileOpen {
        withContext(Dispatchers.Default) { model.countTokens(text) }
    }

    override suspend fun updateGenerationParameters(parameters: GenerationParameters) {
        guard.whileOpen {
            generationParameters = parameters
            sessionOptions = sessionOptions.copy(
                temperature = parameters.temperature,
                topK = parameters.topK,
                topP = parameters.topP,
                minP = parameters.minP,
                seed = parameters.seed,
            )
            closeSession()
        }
    }

    override suspend fun close() = guard.close { closeSession() }

    private fun freshSession(): CeraSession {
        val existing = session
        if (existing != null) {
            existing.reset()
            return existing
        }
        return model.openSession(sessionOptions).also { session = it }
    }

    private fun closeSession() {
        session?.close()
        session = null
    }

    private fun flatten(prompt: List<PromptPart>): String = prompt.joinToString("") { part ->
        when (part) {
            is PromptPart.Text -> part.text
            else -> error("Cera's text sessions take text; got ${part::class.simpleName}")
        }
    }

    private fun grammarFor(constraint: GenerationConstraint?): String? = when (constraint) {
        null -> null
        is GenerationConstraint.JsonSchema -> error("Cera's bindings take a GBNF grammar, not a JSON schema")
    }
}
