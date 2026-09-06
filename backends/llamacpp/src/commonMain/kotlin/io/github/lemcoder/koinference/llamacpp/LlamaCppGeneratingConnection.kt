package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.ConnectionGuard
import io.github.lemcoder.koinference.runtime.GeneratingConnection
import io.github.lemcoder.koinference.runtime.generation.GenerationConstraint
import io.github.lemcoder.koinference.runtime.generation.GenerationParameters
import io.github.lemcoder.koinference.runtime.media.ResponsePart
import io.github.lemcoder.koinference.runtime.text.TokenCounting
import io.github.lemcoder.koinference.llamacpp.internal.CpuPlacement
import io.github.lemcoder.koinference.llamacpp.internal.CpuPlacementSource
import io.github.lemcoder.koinference.llamacpp.internal.LlamaCppBridge
import io.github.lemcoder.koinference.llamacpp.internal.LlamaCppModel
import io.github.lemcoder.koinference.llamacpp.internal.LlamaCppSession
import io.github.lemcoder.koinference.llamacpp.internal.toSessionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * One session over a loaded GGUF model: generation and token counting.
 *
 * This engine only ever emits `ResponsePart.Text`. Each connection owns its own session (KV cache,
 * batch, sampler), so several can be open over one [LlamaCppLoadedModel] at the cost of a KV cache
 * each; a call at a time on each, guarded.
 *
 * The internal session still streams a `Flow` — that is behind the seam. The public [generate]
 * delivers a callback, per the no-`Flow` public-API rule.
 */
class LlamaCppGeneratingConnection internal constructor(
    private val bridge: LlamaCppBridge,
    private val model: LlamaCppModel,
    private val target: String,
    private val systemPrompt: String?,
    private val nCtx: Int,
    private val nThreads: Int,
    private val nPredict: Int,
    parameters: GenerationParameters,
    private val placementPolicy: CpuPlacementSource,
) : GeneratingConnection, TokenCounting {

    override var generationParameters: GenerationParameters = parameters
        private set

    private var session: LlamaCppSession? = null
    private var placement: CpuPlacement? = null
    private val guard = ConnectionGuard { target }

    override suspend fun generate(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
        onPart: (ResponsePart) -> Unit,
    ) {
        val text = prompt.joinToString("") { (it as PromptPart.Text).text }
        guard.whileOpen {
            withContext(Dispatchers.Default) {
                val grammar = grammarFor(constraint)
                val session = currentSession()
                placeThreads(session)
                // Every chunk is one Text part; this engine emits text only.
                session.stream(systemPrompt, text, grammar).map(ResponsePart::Text).collect { onPart(it) }
            }
        }
    }

    override suspend fun countTokens(text: String): Int = guard.whileOpen {
        withContext(Dispatchers.Default) { currentSession().tokenCount(text) }
    }

    /** Sampling is fixed when the session is created, so a change drops and rebuilds it — cheap. */
    override suspend fun updateGenerationParameters(parameters: GenerationParameters) {
        guard.whileOpen {
            if (parameters == generationParameters) return@whileOpen
            generationParameters = parameters
            releaseSession()
        }
    }

    override suspend fun close() {
        guard.close { releaseSession() }
    }

    private fun grammarFor(constraint: GenerationConstraint?): String? = when (constraint) {
        is GenerationConstraint.JsonSchema -> bridge.jsonSchemaToGrammar(constraint.schema)
        null -> null
    }

    private fun currentSession(): LlamaCppSession =
        session ?: openSession().also { session = it; placement = null }

    private fun openSession(): LlamaCppSession {
        val threads = if (nThreads > 0) nThreads else placementPolicy.choose().threads
        return model.openSession(generationParameters.toSessionOptions(nCtx, threads, nPredict))
    }

    private fun placeThreads(session: LlamaCppSession) {
        val chosen = placementPolicy.choose()
        if (chosen == placement) return
        session.setCpuMask(chosen.cpus)
        placement = chosen
    }

    private fun releaseSession() {
        session?.close()
        session = null
    }
}
