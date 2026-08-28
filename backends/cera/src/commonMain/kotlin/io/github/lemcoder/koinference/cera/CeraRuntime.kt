package io.github.lemcoder.koinference.cera

import io.github.lemcoder.koinference.cera.internal.CeraBridge
import io.github.lemcoder.koinference.cera.internal.CeraModel
import io.github.lemcoder.koinference.cera.internal.CeraModelOptions
import io.github.lemcoder.koinference.cera.internal.CeraSession
import io.github.lemcoder.koinference.cera.internal.CeraSessionOptions
import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.GenerationConstraint
import io.github.lemcoder.koinference.runtime.GenerationParameters
import io.github.lemcoder.koinference.runtime.ResponsePart
import io.github.lemcoder.koinference.runtime.RuntimeGuard
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * A loaded Cera model and one session over it.
 *
 * Created by [CeraModelLoader]. All state is behind one [RuntimeGuard]: the model and its session
 * are handles into a Rust engine, and freeing either while a decode is running is a use-after-free
 * rather than an exception.
 */
class CeraRuntime internal constructor(
    private val bridge: CeraBridge,
    private var modelOptions: CeraModelOptions,
    private var model: CeraModel,
    private var sessionOptions: CeraSessionOptions,
    parameters: GenerationParameters = GenerationParameters(),
) : CeraTextRuntime {

    override var generationParameters: GenerationParameters = parameters
        private set

    // Derived rather than stored: where the model runs is a property of how it was loaded, and a
    // second copy is a second thing that can disagree with the engine.
    override val runtimeSettings: RuntimeSettings
        get() = RuntimeSettings(modelOptions.accelerator)

    // Opened lazily and reused: a session holds the KV cache, so making one per call would
    // re-prefill every turn.
    private var session: CeraSession? = null

    private val guard = RuntimeGuard { modelOptions.modelPath }

    override suspend fun generateResponse(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
    ): List<ResponsePart> {
        val text = flatten(prompt)
        val grammar = grammarFor(constraint)

        return guard.whileOpen {
            withContext(Dispatchers.Default) {
                // One part: this engine's text sessions emit text and nothing else. Cera can also
                // decode audio, which would be a second part here and is not wired up.
                listOf(ResponsePart.Text(session().generate(text, grammar)))
            }
        }
    }

    override fun streamResponse(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
    ): Flow<ResponsePart> {
        val text = flatten(prompt)
        return guard.streamWhileOpen {
            val grammar = grammarFor(constraint)
            // Text only, so every chunk is one Text part. Wrapped here rather than in the binding,
            // which speaks Cera's language.
            emitAll(session().stream(text, grammar).map(ResponsePart::Text))
        }
    }

    override suspend fun countTokens(text: String): Int = guard.whileOpen {
        withContext(Dispatchers.Default) { model.countTokens(text) }
    }

    /**
     * Rebuilds the session, because Cera fixes the sampler when one is opened.
     *
     * The KV cache goes with it: the prompt is re-prefilled on the next call.
     */
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

    /**
     * Reloads the weights, because Cera picks its backend when the engine is created.
     *
     * If the reload fails the runtime is left unloaded and says so, rather than reporting a device
     * it is not running on.
     */
    override suspend fun updateRuntimeSettings(settings: RuntimeSettings) {
        guard.whileOpen {
            if (settings.accelerator == modelOptions.accelerator) return@whileOpen

            val options = modelOptions.copy(accelerator = settings.accelerator)
            closeSession()
            model.close()

            model = withContext(Dispatchers.Default) { bridge.openModel(options) }
            modelOptions = options
        }
    }

    internal suspend fun close() = guard.close {
        closeSession()
        model.close()
    }

    private fun session(): CeraSession =
        session ?: model.openSession(sessionOptions).also { session = it }

    private fun closeSession() {
        session?.close()
        session = null
    }

    /** Text only: a GGUF text model has nowhere to put an image, and saying so beats guessing. */
    private fun flatten(prompt: List<PromptPart>): String = prompt.joinToString("") { part ->
        when (part) {
            is PromptPart.Text -> part.text
            else -> error("Cera's text sessions take text; got ${part::class.simpleName}")
        }
    }

    /**
     * Cera constrains decoding with GBNF and exposes no JSON-schema converter through its
     * bindings, so a schema cannot be honoured here.
     */
    private fun grammarFor(constraint: GenerationConstraint?): String? = when (constraint) {
        null -> null
        is GenerationConstraint.JsonSchema ->
            error("Cera's bindings take a GBNF grammar, not a JSON schema")
    }
}
