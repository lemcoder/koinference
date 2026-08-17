package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.GenerationConstraint
import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.PromptPart
import io.github.lemcoder.koinference.RuntimeSettings
import io.github.lemcoder.koinference.StreamingTextRuntime
import io.github.lemcoder.koinference.textOnly
import io.github.lemcoder.koinference.litertlm.internal.EngineOptions
import io.github.lemcoder.koinference.litertlm.internal.LiteRtLmBridge
import io.github.lemcoder.koinference.litertlm.internal.LiteRtLmConversation
import io.github.lemcoder.koinference.litertlm.internal.LiteRtLmEngine
import io.github.lemcoder.koinference.litertlm.internal.toConversationOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A loaded LiteRT-LM model and one conversation over it.
 *
 * Created by [LiteRtLmModelLoader]; the engine is already loaded by the time an instance
 * exists, so every method here is cheap apart from generation itself and a backend change.
 *
 * All state is behind one lock. The engine and the conversation are native memory reached
 * through raw handles, so freeing either while a generation is running is a use-after-free
 * rather than an exception — which is why every method that touches them suspends instead of
 * mutating from whatever thread the caller happened to be on.
 */
class LiteRtLmRuntime internal constructor(
    private val bridge: LiteRtLmBridge,
    engineOptions: EngineOptions,
    private val systemPrompt: String?,
    private var engine: LiteRtLmEngine,
    parameters: GenerationParameters = GenerationParameters(),
    /**
     * Cap on tokens per reply; 0 leaves it to the engine's own budget.
     *
     * Per conversation rather than per call, because LiteRT-LM fixes it when the conversation
     * is created — the same place the sampler is fixed. llama.cpp's n_predict is the
     * counterpart, and a benchmark comparing the two has to set both or it is comparing
     * different amounts of work.
     */
    private val maxOutputTokens: Int = 0,
) : LiteRtLmTextRuntime, StreamingTextRuntime {

    private var engineOptions: EngineOptions = engineOptions

    override var generationParameters: GenerationParameters = parameters
        private set

    // Derived rather than stored: the backend is a property of the engine, and a second copy
    // of it is a second thing that can be true while the engine says otherwise.
    override val runtimeSettings: RuntimeSettings
        get() = RuntimeSettings(engineOptions.backend)

    // Conversations carry prefilled state, so one is opened per runtime and reused across
    // turns. Reopening it is how a parameter change takes effect, since the sampler is fixed
    // when the conversation is created.
    private var conversation: LiteRtLmConversation? = null

    private var closed: Boolean = false
    private val lock = Mutex()

    override suspend fun generateResponse(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
    ): String {
        // Text-only for now. The prebuilt runtime itself can do vision and audio — that code
        // is compiled into it, unlike a source build — but reaching it needs the engine
        // created with a vision/audio backend and the facade taught to pass content parts
        // through, neither of which is wired up. Rejected before the lock: a bad prompt is
        // the caller's mistake and should not queue behind someone else's generation.
        val text = prompt.textOnly("LiteRT-LM")

        val schema = when (constraint) {
            is GenerationConstraint.JsonSchema -> constraint.schema
            null -> null
        }

        return lock.withLock {
            check(!closed) { "This runtime has been unloaded: ${engineOptions.modelPath}" }
            withContext(Dispatchers.Default) {
                explainingSystemPromptFailures {
                    currentConversation().generate(text, schema)
                }
            }
        }
    }

    /**
     * Streams the reply chunk by chunk.
     *
     * Holds the lock for the whole generation, like [generateResponse] does: the conversation
     * carries prefilled state, and a second turn starting half way through this one would
     * interleave into it.
     */
    override fun streamResponse(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
    ): Flow<String> = flow {
        val text = prompt.textOnly("LiteRT-LM")
        val schema = (constraint as? GenerationConstraint.JsonSchema)?.schema

        lock.withLock {
            check(!closed) { "This runtime has been unloaded: ${engineOptions.modelPath}" }
            emitAll(explainingSystemPromptFailures { currentConversation().stream(text, schema) })
        }
    }

    /**
     * Sampling is fixed when the conversation is created, so the conversation is dropped and
     * reopened on the next call — which also drops its prefilled history.
     */
    override suspend fun updateGenerationParameters(parameters: GenerationParameters) {
        lock.withLock {
            check(!closed) { "This runtime has been unloaded: ${engineOptions.modelPath}" }
            if (parameters == generationParameters) return@withLock
            generationParameters = parameters
            releaseConversation()
        }
    }

    /**
     * Changing the backend reloads the model: LiteRT-LM decides where a model runs when the
     * engine is created, so nothing short of a new engine moves it.
     *
     * The old engine is released before the new one is created, to avoid holding two copies of
     * the weights on a device that may not have room for them. If the new engine fails to
     * open, this runtime is left unloaded and the caller has to load the model again.
     */
    override suspend fun updateRuntimeSettings(settings: RuntimeSettings) {
        lock.withLock {
            check(!closed) { "This runtime has been unloaded: ${engineOptions.modelPath}" }
            if (settings.backend == engineOptions.backend) return@withLock

            releaseConversation()
            val reopened = engineOptions.copy(backend = settings.backend)
            withContext(Dispatchers.Default) {
                engine.close()
                try {
                    engine = bridge.openEngine(reopened)
                    engineOptions = reopened
                } catch (failure: Throwable) {
                    closed = true
                    throw IllegalStateException(
                        "Could not reopen ${reopened.modelPath} on ${settings.backend}; " +
                            "the runtime is unloaded and has to be loaded again",
                        failure,
                    )
                }
            }
        }
    }

    override suspend fun resetConversation() {
        lock.withLock {
            check(!closed) { "This runtime has been unloaded: ${engineOptions.modelPath}" }
            releaseConversation()
        }
    }

    /** Releases the conversation and the engine. Called by the loader; idempotent. */
    internal suspend fun close() {
        lock.withLock {
            if (closed) return@withLock
            closed = true
            releaseConversation()
            engine.close()
        }
    }

    /**
     * Adds the likely cause when a generation fails and a system prompt is set.
     *
     * Whether a model accepts a system role is a property of its chat template, and the
     * runtime's own report is "send_message failed" either way. LFM2.5-1.2B-Instruct refuses
     * one where SmolLM2-135M-Instruct accepts it, so the difference is worth naming rather
     * than leaving to whoever next spends an afternoon on it.
     */
    private inline fun <T> explainingSystemPromptFailures(block: () -> T): T = try {
        block()
    } catch (failure: IllegalStateException) {
        if (systemPrompt == null) throw failure
        throw IllegalStateException(
            "${failure.message} — this runtime was given a system prompt, and some models' chat " +
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
