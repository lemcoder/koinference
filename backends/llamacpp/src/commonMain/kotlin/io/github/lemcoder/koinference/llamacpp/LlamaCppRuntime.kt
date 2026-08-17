package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.GenerationConstraint
import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.GenerationTelemetry
import io.github.lemcoder.koinference.InstrumentedRuntime
import io.github.lemcoder.koinference.TelemetrySource
import io.github.lemcoder.koinference.InferenceBackend
import io.github.lemcoder.koinference.PromptPart
import io.github.lemcoder.koinference.RuntimeSettings
import io.github.lemcoder.koinference.textOnly
import io.github.lemcoder.koinference.llamacpp.gguf.GgufMetadata
import io.github.lemcoder.koinference.llamacpp.gguf.GgufParser
import io.github.lemcoder.koinference.llamacpp.gguf.readFileBytes
import io.github.lemcoder.koinference.llamacpp.internal.LlamaBackend
import io.github.lemcoder.koinference.llamacpp.internal.llamaGenerate
import io.github.lemcoder.koinference.llamacpp.internal.llamaLastDecodeMicros
import io.github.lemcoder.koinference.llamacpp.internal.llamaLastDecodeTokens
import io.github.lemcoder.koinference.llamacpp.internal.llamaLastPrefillMicros
import io.github.lemcoder.koinference.llamacpp.internal.llamaLastPromptTokens
import io.github.lemcoder.koinference.llamacpp.internal.llamaLastTtftMicros
import io.github.lemcoder.koinference.llamacpp.internal.llamaJsonSchemaToGrammar
import io.github.lemcoder.koinference.llamacpp.internal.llamaModelFree
import io.github.lemcoder.koinference.llamacpp.internal.llamaModelLoad
import io.github.lemcoder.koinference.llamacpp.internal.llamaSessionCreate
import io.github.lemcoder.koinference.llamacpp.internal.llamaSessionFree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Mirrors koi_default_session_params(), so leaving a knob unset means the same on both sides. */
private const val DEFAULT_TEMP = 0.8f
private const val DEFAULT_TOP_K = 40
private const val DEFAULT_MIN_P = 0.05f

/**
 * Offload everything the model has. llama.cpp clamps to the layer count, so a number larger
 * than any real model is how "all of it" is spelled.
 */
private const val ALL_GPU_LAYERS = 999

/**
 * A loaded llama.cpp model and one session over it.
 *
 * Created by [LlamaCppModelLoader], which has already loaded the weights by the time an
 * instance exists — [generateResponse] then creates the session on first use.
 */
class LlamaCppRuntime internal constructor(
    private val modelPath: String,
    private var modelHandle: Long,
    private val systemPrompt: String?,
    private val nCtx: Int,
    private val nThreads: Int,
    private val nPredict: Int,
    settings: RuntimeSettings,
) : LlamaCppTextRuntime, InstrumentedRuntime {

    override var generationParameters: GenerationParameters = GenerationParameters()
        private set

    override var runtimeSettings: RuntimeSettings = settings
        private set

    // The session owns the KV cache and the batch, so a second generation running against it
    // concurrently would decode into the first one's state. One session per runtime, one
    // generation at a time.
    private var sessionHandle: Long = 0L
    private val lock = Mutex()
    private var closed: Boolean = false

    override var lastGeneration: GenerationTelemetry? = null
        private set

    override suspend fun generateResponse(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
    ): String {
        // The facade is text-only: llama.cpp has mtmd upstream, but koi_generate takes a
        // single user string and nothing wires the multimodal projector. Rejected before the
        // lock: a bad prompt is the caller's mistake, not something to queue for.
        val text = prompt.textOnly("llama.cpp")

        return lock.withLock {
            // Under the lock, so that an unload racing this cannot pass the check and free the
            // session between here and the call into the facade.
            check(!closed) { "This runtime has been unloaded: $modelPath" }
            withContext(Dispatchers.Default) {
                // Conversion happens here rather than in Kotlin: llama.cpp ships the
                // schema-to-GBNF compiler its own sampler was built against.
                val grammar = when (constraint) {
                    is GenerationConstraint.JsonSchema -> llamaJsonSchemaToGrammar(constraint.schema)
                        .ifEmpty { throw IllegalArgumentException("Not a convertible JSON schema: ${constraint.schema}") }

                    null -> null
                }

                val session = currentSession()
                // A -1 from the facade is an empty string here, which is indistinguishable from
                // a model that produced nothing, so it is raised instead.
                val reply = llamaGenerate(session, systemPrompt, text, grammar)
                    .ifEmpty { error("llama.cpp generated nothing for $modelPath") }
                // Read while the session is still the one that produced the reply: any later
                // parameter change frees it and the numbers go with it.
                lastGeneration = readTelemetry(session)
                reply
            }
        }
    }

    /**
     * Sampling is fixed when the session is created, so the session is dropped and rebuilt on
     * the next call — cheap, since the weights stay loaded.
     */
    override suspend fun updateGenerationParameters(parameters: GenerationParameters) {
        lock.withLock {
            generationParameters = parameters
            releaseSession()
        }
    }

    /**
     * Changing the backend additionally drops the *model*: offload is a load-time decision in
     * llama.cpp. The reload is deferred to the next generation rather than run here, where it
     * would block the caller on hundreds of megabytes of I/O from a non-suspending function.
     */
    override suspend fun updateRuntimeSettings(settings: RuntimeSettings) {
        lock.withLock {
            val backendChanged = settings.backend != runtimeSettings.backend
            runtimeSettings = settings
            releaseSession()
            if (backendChanged) releaseModel()
        }
    }

    suspend fun readGgufMetadata(): GgufMetadata = GgufParser.parse(readFileBytes(modelPath))

    /**
     * Releases the session and the model. Called by the loader; idempotent.
     *
     * Suspends for the same reason the updates do: a generation holding the session has to
     * finish before the session is freed underneath it.
     */
    internal suspend fun close() {
        lock.withLock {
            if (closed) return@withLock
            closed = true
            releaseSession()
            releaseModel()
        }
    }

    private fun currentSession(): Long {
        if (modelHandle == 0L) modelHandle = loadModel(modelPath, runtimeSettings)
        if (sessionHandle == 0L) {
            sessionHandle = llamaSessionCreate(
                modelHandle = modelHandle,
                nCtx = nCtx,
                nThreads = nThreads,
                nPredict = nPredict,
                // topP and seed are not here: koi_session_create takes neither, so a caller
                // that sets them on this backend is ignored rather than surprised by one knob
                // standing in for another.
                temp = generationParameters.temperature?.toFloat() ?: DEFAULT_TEMP,
                topK = generationParameters.topK ?: DEFAULT_TOP_K,
                minP = generationParameters.minP?.toFloat() ?: DEFAULT_MIN_P,
            )
            check(sessionHandle != 0L) { "llama.cpp could not create a session for $modelPath" }
        }
        return sessionHandle
    }

    /**
     * Reads the facade's `koi_last_*` getters, mapping their -1 sentinel to null.
     *
     * Rates are derived here rather than in C: llama.cpp reports counts and durations, and
     * computing tokens/sec from them at the call site keeps the formula visible next to the
     * numbers it uses. Prefill rate is the prompt divided by prefill time; decode rate counts
     * from the first token, matching what [GenerationTelemetry.decodeMs] measures.
     */
    private fun readTelemetry(session: Long): GenerationTelemetry {
        fun stat(value: Int): Int? = value.takeIf { it >= 0 }

        val promptTokens = stat(llamaLastPromptTokens(session))
        val decodeTokens = stat(llamaLastDecodeTokens(session))
        val prefillMs = stat(llamaLastPrefillMicros(session))?.let { it / 1000.0 }
        val decodeMs = stat(llamaLastDecodeMicros(session))?.let { it / 1000.0 }

        return GenerationTelemetry(
            // The facade stamps these inside its decode loop, not at this boundary.
            source = TelemetrySource.ENGINE,
            timeToFirstTokenMs = stat(llamaLastTtftMicros(session))?.let { it / 1000.0 },
            prefillMs = prefillMs,
            decodeMs = decodeMs,
            promptTokens = promptTokens,
            decodeTokens = decodeTokens,
            prefillTokensPerSecond = ratePerSecond(promptTokens, prefillMs),
            decodeTokensPerSecond = ratePerSecond(decodeTokens, decodeMs),
            // llama.cpp has no equivalent: loading is koi_model_load, timed by the caller.
            engineInitMs = null,
        )
    }

    private fun ratePerSecond(tokens: Int?, millis: Double?): Double? =
        if (tokens == null || millis == null || millis <= 0.0) null else tokens * 1000.0 / millis

    private fun releaseSession() {
        if (sessionHandle != 0L) llamaSessionFree(sessionHandle)
        sessionHandle = 0L
    }

    private fun releaseModel() {
        if (modelHandle != 0L) llamaModelFree(modelHandle)
        modelHandle = 0L
    }

    internal companion object {
        fun load(
            modelPath: String,
            systemPrompt: String?,
            settings: RuntimeSettings,
            nCtx: Int,
            nThreads: Int,
            nPredict: Int,
        ): LlamaCppRuntime = LlamaCppRuntime(
            modelPath = modelPath,
            modelHandle = loadModel(modelPath, settings),
            systemPrompt = systemPrompt,
            nCtx = nCtx,
            nThreads = nThreads,
            nPredict = nPredict,
            settings = settings,
        )

        private fun loadModel(modelPath: String, settings: RuntimeSettings): Long {
            LlamaBackend.ensureInitialized()
            val handle = llamaModelLoad(
                path = modelPath,
                nGpuLayers = when (settings.backend) {
                    InferenceBackend.CPU -> 0
                    // A build with no GPU backend compiled in ignores this rather than failing,
                    // so GPU on such a target is CPU inference and not an error.
                    InferenceBackend.GPU -> ALL_GPU_LAYERS
                },
            )
            check(handle != 0L) { "llama.cpp could not load $modelPath" }
            return handle
        }
    }
}
