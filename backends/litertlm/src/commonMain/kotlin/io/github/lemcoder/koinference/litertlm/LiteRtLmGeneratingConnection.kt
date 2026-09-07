package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.ConnectionGuard
import io.github.lemcoder.koinference.runtime.GeneratingConnection
import io.github.lemcoder.koinference.runtime.generation.GenerationConstraint
import io.github.lemcoder.koinference.runtime.generation.GenerationParameters
import io.github.lemcoder.koinference.runtime.media.ResponsePart
import io.github.lemcoder.koinference.runtime.text.TokenCounting
import io.github.lemcoder.koinference.litertlm.internal.LiteRtLmConversation
import io.github.lemcoder.koinference.litertlm.internal.LiteRtLmEngine
import io.github.lemcoder.koinference.litertlm.internal.toConversationOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * One conversation over a loaded LiteRT-LM engine.
 *
 * The conversation carries prefilled state and is reused across turns; [resetConversation] and a
 * parameter change reopen it. The engine (the weights) belongs to [LiteRtLmLoadedModel] and is not
 * touched here.
 */
class LiteRtLmGeneratingConnection internal constructor(
    private val engine: LiteRtLmEngine,
    private val target: String,
    private val systemPrompt: String?,
    private val maxOutputTokens: Int,
    parameters: GenerationParameters,
) : GeneratingConnection, TokenCounting {

    override var generationParameters: GenerationParameters = parameters
        private set

    private var conversation: LiteRtLmConversation? = null
    private val guard = ConnectionGuard { target }

    override val isClosed: Boolean get() = guard.isClosed

    override suspend fun generate(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
        onPart: (ResponsePart) -> Unit,
    ) {
        val text = prompt.joinToString("") { (it as PromptPart.Text).text }
        val schema = (constraint as? GenerationConstraint.JsonSchema)?.schema
        guard.whileOpen {
            withContext(Dispatchers.Default) {
                explainingSystemPromptFailures {
                    currentConversation().stream(text, schema).map(ResponsePart::Text).collect { onPart(it) }
                }
            }
        }
    }

    override suspend fun countTokens(text: String): Int = guard.whileOpen {
        withContext(Dispatchers.Default) { engine.tokenCount(text) }
    }

    override suspend fun updateGenerationParameters(parameters: GenerationParameters) {
        guard.whileOpen {
            if (parameters == generationParameters) return@whileOpen
            generationParameters = parameters
            releaseConversation()
        }
    }

    /** Drop the prefilled history and start the next turn fresh. LiteRT-LM specific. */
    suspend fun resetConversation() {
        guard.whileOpen { releaseConversation() }
    }

    override suspend fun close() {
        guard.close { releaseConversation() }
    }

    private inline fun <T> explainingSystemPromptFailures(block: () -> T): T = try {
        block()
    } catch (failure: IllegalStateException) {
        if (systemPrompt == null) throw failure
        throw IllegalStateException(
            "${failure.message} — this connection was given a system prompt, and some models' chat " +
                "templates reject one. Try loading without a system prompt to confirm.",
            failure,
        )
    }

    private fun currentConversation(): LiteRtLmConversation =
        conversation ?: engine
            .openConversation(generationParameters.toConversationOptions(systemPrompt, maxOutputTokens))
            .also { conversation = it }

    private fun releaseConversation() {
        conversation?.close()
        conversation = null
    }
}
