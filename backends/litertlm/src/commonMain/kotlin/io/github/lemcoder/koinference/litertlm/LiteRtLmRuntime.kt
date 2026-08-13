package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.GenerationConstraint
import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.InferenceBackend
import io.github.lemcoder.koinference.PromptPart
import io.github.lemcoder.koinference.RuntimeSettings
import io.github.lemcoder.koinference.textOnly
import io.github.lemcoder.koinference.litertlm.internal.BACKEND_CPU
import io.github.lemcoder.koinference.litertlm.internal.BACKEND_GPU
import io.github.lemcoder.koinference.litertlm.internal.LiteRtLmConversation
import io.github.lemcoder.koinference.litertlm.internal.LiteRtLmEngine
import io.github.lemcoder.koinference.litertlm.internal.closeConversation
import io.github.lemcoder.koinference.litertlm.internal.closeEngine
import io.github.lemcoder.koinference.litertlm.internal.generate
import io.github.lemcoder.koinference.litertlm.internal.openConversation
import io.github.lemcoder.koinference.litertlm.internal.openEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Defaults chosen to match the facade's koilm_default_session_params(). */
private const val DEFAULT_TOP_K = 40
private const val DEFAULT_TOP_P = 0.95f
private const val DEFAULT_TEMPERATURE = 0.8f

/**
 * A loaded LiteRT-LM model and one conversation over it.
 *
 * Created by [LiteRtLmModelLoader]; the engine is already loaded by the time an instance
 * exists, so every method here is cheap apart from generation itself.
 */
class LiteRtLmRuntime internal constructor(
    private val modelPath: String,
    private val engine: LiteRtLmEngine,
    private val systemPrompt: String?,
    settings: RuntimeSettings,
) : LiteRtLmTextRuntime {

    var generationParameters: GenerationParameters = GenerationParameters()
        private set

    var runtimeSettings: RuntimeSettings = settings
        private set

    // Conversations carry prefilled state, so one is opened per runtime and reused across
    // turns. Reopening it is how a settings change takes effect, since the sampler is fixed
    // when the conversation is created.
    private var conversation: LiteRtLmConversation? = null
    private var closed: Boolean = false

    override suspend fun generateResponse(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
    ): String {
        check(!closed) { "This runtime has been unloaded: $modelPath" }

        // Text-only for now. The prebuilt runtime itself can do vision and audio — that code
        // is compiled into it, unlike a source build — but reaching it needs the engine
        // created with a vision/audio backend and the facade taught to pass content parts
        // through, neither of which is wired up.
        val text = prompt.textOnly("LiteRT-LM")

        val schema = when (constraint) {
            is GenerationConstraint.JsonSchema -> constraint.schema
            null -> null
        }

        return withContext(Dispatchers.Default) {
            generate(currentConversation(), text, schema)
        }
    }

    override fun updateGenerationParameters(parameters: GenerationParameters) {
        generationParameters = parameters
        releaseConversation()
    }

    override fun updateRuntimeSettings(settings: RuntimeSettings) {
        runtimeSettings = settings
        releaseConversation()
    }

    private fun currentConversation(): LiteRtLmConversation =
        conversation ?: openConversation(
            engine = engine,
            maxTokens = 0,
            topK = generationParameters.topK ?: DEFAULT_TOP_K,
            // minP is what the common GenerationParameters offers and top-p is what
            // LiteRT-LM's sampler takes. They are different knobs, so an explicit minP is
            // not silently reinterpreted — it is left to the sampler default.
            topP = DEFAULT_TOP_P,
            temp = DEFAULT_TEMPERATURE,
            systemPrompt = systemPrompt,
        ).also { conversation = it }

    private fun releaseConversation() {
        conversation?.let { closeConversation(it) }
        conversation = null
    }

    /** Releases the conversation and the engine. Called by the loader; idempotent. */
    internal fun close() {
        if (closed) return
        releaseConversation()
        closeEngine(engine)
        closed = true
    }

    internal companion object {
        fun load(
            modelPath: String,
            cacheDir: String?,
            systemPrompt: String?,
            settings: RuntimeSettings,
            nThreads: Int,
            maxTokens: Int,
        ): LiteRtLmRuntime {
            val backend = when (settings.backend) {
                InferenceBackend.CPU -> BACKEND_CPU
                InferenceBackend.GPU -> BACKEND_GPU
            }
            val engine = openEngine(modelPath, cacheDir, backend, nThreads, maxTokens)
            return LiteRtLmRuntime(modelPath, engine, systemPrompt, settings)
        }
    }
}
