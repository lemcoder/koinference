package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.runtime.GenerationConstraint
import io.github.lemcoder.koinference.runtime.GenerationParameters
import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.RuntimeGuard
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import io.github.lemcoder.koinference.llamacpp.gguf.GgufMetadata
import io.github.lemcoder.koinference.llamacpp.gguf.GgufParser
import io.github.lemcoder.koinference.llamacpp.gguf.readFileBytes
import io.github.lemcoder.koinference.llamacpp.internal.CpuPlacement
import io.github.lemcoder.koinference.llamacpp.internal.CpuPlacementSource
import io.github.lemcoder.koinference.llamacpp.internal.platformCpuPlacement
import io.github.lemcoder.koinference.llamacpp.internal.LlamaCppBridge
import io.github.lemcoder.koinference.llamacpp.internal.LlamaCppModel
import io.github.lemcoder.koinference.llamacpp.internal.LlamaCppSession
import io.github.lemcoder.koinference.llamacpp.internal.ModelOptions
import io.github.lemcoder.koinference.llamacpp.internal.toSessionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.withContext

/**
 * A loaded llama.cpp model and one session over it.
 *
 * Created by [LlamaCppModelLoader]; the weights are already loaded by the time an instance
 * exists, and the session is opened on first use.
 *
 * All state is behind one [RuntimeGuard]. The model and the session are native memory reached
 * through the bridge, so freeing either while a generation is running is a use-after-free rather
 * than an exception — which is why every method that touches them suspends.
 */
class LlamaCppRuntime internal constructor(
    private val bridge: LlamaCppBridge,
    modelOptions: ModelOptions,
    private val systemPrompt: String?,
    private var model: LlamaCppModel,
    private val nCtx: Int,
    private val nThreads: Int,
    private val nPredict: Int,
    parameters: GenerationParameters = GenerationParameters(),
    private val placementPolicy: CpuPlacementSource = platformCpuPlacement(),
) : LlamaCppTextRuntime {

    private var modelOptions: ModelOptions = modelOptions

    override var generationParameters: GenerationParameters = parameters
        private set

    // Derived rather than stored: where the model runs is a property of the loaded model, and a
    // second copy of it is a second thing that can be true while the model says otherwise.
    override val runtimeSettings: RuntimeSettings
        get() = RuntimeSettings(modelOptions.accelerator)

    // The session owns the KV cache, the batch and the sampler, so it is opened once and reused.
    // Rebuilding it is how a parameter change takes effect, since the sampler is fixed when the
    // session is created.
    private var session: LlamaCppSession? = null

    private val guard = RuntimeGuard { modelOptions.modelPath }

    // What the session is currently pinned to, so a re-evaluation that changes nothing costs
    // nothing. Null until the first decode has placed the threads.
    private var placement: CpuPlacement? = null

    override suspend fun generateResponse(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
    ): String {
        // Flattened before the guard, so a bad prompt does not queue behind someone else's
        // generation.
        val text = prompt.joinToString("") { (it as PromptPart.Text).text }

        return guard.whileOpen {
            withContext(Dispatchers.Default) {
                val grammar = grammarFor(constraint)
                val session = currentSession()
                placeThreads(session)
                session.generate(systemPrompt, text, grammar)
                    .ifEmpty { error("llama.cpp generated nothing for ${modelOptions.modelPath}") }
            }
        }
    }

    /**
     * Streams the reply a token at a time.
     *
     * Holds the guard for the whole generation: the session carries the KV cache and the sampler,
     * and a second call decoding into it half way through this one would corrupt both.
     */
    override fun streamResponse(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
    ): Flow<String> {
        val text = prompt.joinToString("") { (it as PromptPart.Text).text }
        return guard.streamWhileOpen {
            val grammar = grammarFor(constraint)
            val session = currentSession()
            placeThreads(session)
            emitAll(session.stream(systemPrompt, text, grammar))
        }
    }

    /**
     * Counts with the model's own vocabulary.
     *
     * Needs a session, which is where the context lives, so the first call on a fresh runtime
     * opens one — the same session a generation would use, not an extra one.
     */
    override suspend fun countTokens(text: String): Int = guard.whileOpen {
        withContext(Dispatchers.Default) { currentSession().tokenCount(text) }
    }

    /**
     * Sampling is fixed when the session is created, so the session is dropped and rebuilt on the
     * next call — cheap, since the weights stay loaded.
     */
    override suspend fun updateGenerationParameters(parameters: GenerationParameters) {
        guard.whileOpen {
            if (parameters == generationParameters) return@whileOpen
            generationParameters = parameters
            releaseSession()
        }
    }

    /**
     * Changing the backend reloads the model: llama.cpp decides GPU offload when the weights are
     * loaded (`llama_model_params.n_gpu_layers`), so nothing short of a new model moves it.
     *
     * The old model is released before the new one is loaded, to avoid holding two copies of the
     * weights on a device that may not have room for them. If the reload fails, this runtime is
     * left unloaded and the caller has to load the model again — the same contract
     * `:backends:litertlm` follows for the same reason.
     */
    override suspend fun updateRuntimeSettings(settings: RuntimeSettings) {
        guard.whileOpen {
            if (settings.accelerator == modelOptions.accelerator) return@whileOpen

            releaseSession()
            val reloaded = modelOptions.copy(accelerator = settings.accelerator)
            withContext(Dispatchers.Default) {
                model.close()
                try {
                    model = bridge.openModel(reloaded)
                    modelOptions = reloaded
                } catch (failure: Throwable) {
                    guard.markClosed()
                    throw IllegalStateException(
                        "Could not reload ${reloaded.modelPath} on ${settings.accelerator}; " +
                            "the runtime is unloaded and has to be loaded again",
                        failure,
                    )
                }
            }
        }
    }

    suspend fun readGgufMetadata(): GgufMetadata =
        GgufParser.parse(readFileBytes(modelOptions.modelPath))

    /** Releases the session and the model. Called by the loader; idempotent. */
    internal suspend fun close() {
        guard.close {
            releaseSession()
            model.close()
        }
    }

    private fun grammarFor(constraint: GenerationConstraint?): String? = when (constraint) {
        // Converted by the facade rather than in Kotlin: llama.cpp ships the schema-to-GBNF
        // compiler its own sampler was built against.
        is GenerationConstraint.JsonSchema -> bridge.jsonSchemaToGrammar(constraint.schema)
        null -> null
    }

    private fun currentSession(): LlamaCppSession =
        session ?: openSession().also {
            session = it
            placement = null
        }

    /**
     * Opens a session with the worker count this platform's placement asks for.
     *
     * The count comes from the same decision as the mask, because on an unpinned platform it *is*
     * the whole decision — Darwin cannot pin, so `cores - 2` is all its policy has to say. Letting
     * the facade choose instead would put that rule in C, next to a different one for Android, and
     * the two would drift.
     *
     * An explicit `nThreads` from the caller still wins: someone who measured their own workload
     * should not be overridden by a default.
     */
    private fun openSession(): LlamaCppSession {
        val threads = if (nThreads > 0) nThreads else placementPolicy.choose().threads
        return model.openSession(generationParameters.toSessionOptions(nCtx, threads, nPredict))
    }

    /**
     * Place the decode threads, re-deciding if the machine has changed underneath us.
     *
     * Called immediately before every decode rather than once at load, because the answer expires:
     * Android moves an app between cpusets, so a mask chosen while in the foreground names cores
     * the process is forbidden from touching once it is backgrounded — `foreground` is typically
     * every core but the prime one, `background` the little cluster. Pinning to a forbidden core
     * fails rather than degrading, so the pin has to be refreshed, and a decode boundary is the
     * only safe moment: the pool is in use during one.
     *
     * Choosing is a handful of small file reads, and re-pinning only happens when the decision
     * actually changed, so the steady state costs those reads and nothing else.
     */
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
