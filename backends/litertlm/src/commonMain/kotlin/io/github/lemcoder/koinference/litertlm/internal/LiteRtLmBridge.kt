package io.github.lemcoder.koinference.litertlm.internal

import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.GenerationTelemetry
import io.github.lemcoder.koinference.InferenceBackend

/**
 * The seam between the common runtime and whichever LiteRT-LM binding a target has.
 *
 * It is deliberately not the C API's shape. Apple targets reach LiteRT-LM through the facade
 * in `native/` and hold raw pointers; Android cannot, because the runtime it ships
 * (`liblitertlm_jni.so` in the AAR) exports only its 24 `Java_..._LiteRtLmJni_*` entry points
 * and no `litert_lm_*` C symbols at all — its visibility is pinned by a version script. So the
 * Android leg goes through Google's own Kotlin API and holds objects.
 *
 * Interfaces rather than `expect class` handles: with expect/actual, a handle can only be
 * produced by a platform, so no test on any platform could stand in for the runtime and
 * everything in [io.github.lemcoder.koinference.litertlm.LiteRtLmRuntime] would need a 136 MB
 * model to exercise. The nesting (bridge opens engines, engines open conversations,
 * conversations generate) matches the lifetime nesting of the things themselves, so a handle
 * cannot be used without the thing that owns it.
 *
 * Every function here throws on failure; callers do not check for null or zero.
 */
internal interface LiteRtLmBridge {
    fun openEngine(options: EngineOptions): LiteRtLmEngine
}

/** An engine with a model loaded into it. */
internal interface LiteRtLmEngine {
    fun openConversation(options: ConversationOptions): LiteRtLmConversation

    /** Releases the model. Calling anything on the engine afterwards is undefined. */
    fun close()
}

/** One conversation over an engine, carrying its own prefilled state. */
internal interface LiteRtLmConversation {
    /**
     * Send one message and wait for the reply (blocking).
     *
     * @param jsonSchema JSON schema for constrained decoding, or null for unconstrained.
     */
    fun generate(prompt: String, jsonSchema: String?): GeneratedReply

    fun close()
}

/**
 * A reply and whatever the binding could measure about producing it.
 *
 * @property text the assistant's text, already unwrapped from whatever envelope the platform's
 *           binding returns.
 * @property telemetry null on a binding that cannot measure. The C API hangs its benchmark
 *           info off a Session and the facade drives a Conversation, which never exposes one,
 *           so the Apple leg reports nothing rather than a zero that reads like a measurement.
 */
internal class GeneratedReply(
    val text: String,
    val telemetry: GenerationTelemetry?,
)

/** The binding this target was compiled with. */
internal expect fun platformBridge(): LiteRtLmBridge

internal data class EngineOptions(
    val modelPath: String,
    val cacheDir: String? = null,
    val backend: InferenceBackend = InferenceBackend.CPU,
    /** CPU threads; 0 leaves the engine default. */
    val nThreads: Int = 0,
    /** Engine-wide token budget; 0 uses the model's own. */
    val maxTokens: Int = 0,
)

internal data class ConversationOptions(
    val systemPrompt: String? = null,
    /** Max tokens per reply; 0 uses the engine's budget. */
    val maxTokens: Int = 0,
    val topK: Int = DEFAULT_TOP_K,
    val topP: Float = DEFAULT_TOP_P,
    val temperature: Float = DEFAULT_TEMPERATURE,
    /** null leaves the runtime's own seeding, which differs between the two legs. */
    val seed: Int? = null,
)

// Both legs need concrete numbers — Android's SamplerConfig has no default for the first
// three — so the defaults live here rather than being read from one leg and copied into the
// other. koilm_default_session_params() returns exactly these; SessionDefaultsTest fails if
// the facade ever drifts from them.
internal const val DEFAULT_TOP_K = 40
internal const val DEFAULT_TOP_P = 0.95f
internal const val DEFAULT_TEMPERATURE = 0.8f

/**
 * Map the common sampling knobs onto a conversation.
 *
 * [GenerationParameters.minP] is dropped rather than passed as top-p: they are different
 * knobs, and reinterpreting one as the other would make a caller's explicit setting mean
 * something it did not ask for.
 */
internal fun GenerationParameters.toConversationOptions(
    systemPrompt: String?,
    maxTokens: Int = 0,
): ConversationOptions = ConversationOptions(
    systemPrompt = systemPrompt,
    maxTokens = maxTokens,
    topK = topK ?: DEFAULT_TOP_K,
    topP = topP?.toFloat() ?: DEFAULT_TOP_P,
    temperature = temperature?.toFloat() ?: DEFAULT_TEMPERATURE,
    seed = seed,
)
