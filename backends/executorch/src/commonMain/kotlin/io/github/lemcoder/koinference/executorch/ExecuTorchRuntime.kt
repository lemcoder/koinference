package io.github.lemcoder.koinference.executorch

import io.github.lemcoder.koinference.executorch.internal.ExecuTorchBridge
import io.github.lemcoder.koinference.executorch.internal.ExecuTorchModel
import io.github.lemcoder.koinference.executorch.internal.ExecuTorchModelOptions
import io.github.lemcoder.koinference.executorch.internal.ExecuTorchSession
import io.github.lemcoder.koinference.executorch.internal.ExecuTorchSessionOptions
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
 * A loaded ExecuTorch program and the generations run over it.
 *
 * All state is behind one [RuntimeGuard]: `LlmModule` holds native memory, and freeing it while a
 * decode is running is a use-after-free rather than an exception.
 */
class ExecuTorchRuntime internal constructor(
    private val bridge: ExecuTorchBridge,
    private var modelOptions: ExecuTorchModelOptions,
    private var model: ExecuTorchModel,
    private var sessionOptions: ExecuTorchSessionOptions,
    parameters: GenerationParameters = GenerationParameters(),
) : ExecuTorchTextRuntime {

    override var generationParameters: GenerationParameters = parameters
        private set

    /**
     * Always the CPU, and said rather than guessed.
     *
     * Where a `.pte` runs is decided when it is *exported* — the delegate is compiled into the
     * program — so this backend has no device to move a model to. [updateRuntimeSettings] refuses
     * anything else rather than accepting it and changing nothing.
     */
    override val runtimeSettings: RuntimeSettings
        get() = RuntimeSettings(io.github.lemcoder.koinference.runtime.Accelerator.CPU)

    private var session: ExecuTorchSession? = null

    private val guard = RuntimeGuard { modelOptions.modelPath }

    override suspend fun generateResponse(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
    ): List<ResponsePart> {
        val text = flatten(prompt)
        refuse(constraint)

        return guard.whileOpen {
            withContext(Dispatchers.Default) {
                listOf(ResponsePart.Text(session().generate(text)))
            }
        }
    }

    override fun streamResponse(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
    ): Flow<ResponsePart> {
        val text = flatten(prompt)
        return guard.streamWhileOpen {
            refuse(constraint)
            emitAll(session().stream(text).map(ResponsePart::Text))
        }
    }

    /**
     * Temperature is fixed when the module is constructed, so this reloads the program.
     *
     * The other knobs are not applied and not pretended about: see [ExecuTorch.honours].
     */
    override suspend fun updateGenerationParameters(parameters: GenerationParameters) {
        guard.whileOpen {
            generationParameters = parameters
            sessionOptions = sessionOptions.copy(
                temperature = parameters.temperature ?: sessionOptions.temperature,
            )

            val options = modelOptions.copy(
                temperature = parameters.temperature ?: modelOptions.temperature,
            )
            closeSession()
            model.close()
            model = withContext(Dispatchers.Default) { bridge.openModel(options) }
            modelOptions = options
        }
    }

    override suspend fun updateRuntimeSettings(settings: RuntimeSettings) {
        guard.whileOpen {
            check(settings.accelerator == io.github.lemcoder.koinference.runtime.Accelerator.CPU) {
                "ExecuTorch runs where the .pte was exported to run; ${settings.accelerator} " +
                    "cannot be chosen at load time"
            }
        }
    }

    /**
     * Tokens the engine reported for the reply it last produced; -1 for any other text.
     *
     * Narrower than the contract's "tokens in [text]", and deliberately so. ExecuTorch's binding
     * exposes no tokenizer to count arbitrary text with — it reports, through `onStats`, what its
     * own tokenizer produced for the generation just finished. That is the same quantity the other
     * backends compute by re-tokenizing a reply, so a `tok/s` column stays comparable; it simply
     * cannot be asked about a prompt.
     *
     * -1 rather than an exception because the harness already reads a negative count as "this engine
     * did not say", which is exactly what it means here.
     */
    override suspend fun countTokens(text: String): Int = guard.whileOpen {
        session().generatedTokens(text) ?: -1
    }

    internal suspend fun close() = guard.close {
        closeSession()
        model.close()
    }

    private fun session(): ExecuTorchSession =
        session ?: model.openSession(sessionOptions).also { session = it }

    private fun closeSession() {
        session?.close()
        session = null
    }

    /** Text only: a `.pte` text program has nowhere to put an image, and saying so beats guessing. */
    private fun flatten(prompt: List<PromptPart>): String = prompt.joinToString("") { part ->
        when (part) {
            is PromptPart.Text -> part.text
            else -> error("ExecuTorch's text programs take text; got ${part::class.simpleName}")
        }
    }

    /** No grammar, no schema: this binding exposes no constrained decoding at all. */
    private fun refuse(constraint: GenerationConstraint?) {
        if (constraint != null) {
            error("ExecuTorch's Android binding exposes no constrained decoding")
        }
    }
}
