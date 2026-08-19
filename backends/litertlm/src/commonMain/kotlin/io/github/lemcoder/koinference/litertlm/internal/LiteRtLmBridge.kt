package io.github.lemcoder.koinference.litertlm.internal

import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.Accelerator
import kotlinx.coroutines.flow.Flow

/**
 * The seam between the common runtime and whichever LiteRT-LM binding a target has.
 *
 * Both legs bind the same C facade — cinterop on Apple, generated JNI bridges on Android — and
 * this is the shape `:backends:llamacpp` uses too. See `docs/backends.md` for why it is
 * interfaces rather than `expect class` handles, and what a third backend has to fill in.
 *
 * Every function here throws on failure; callers do not check for null or zero.
 */
internal interface LiteRtLmBridge {
    fun openEngine(options: EngineOptions): LiteRtLmEngine
}

/** An engine with a model loaded into it. */
internal interface LiteRtLmEngine {
    fun openConversation(options: ConversationOptions): LiteRtLmConversation

    /**
     * Tokens in [text], according to the model's own tokenizer.
     *
     * On the engine rather than the conversation because that is where the C API puts it, and
     * because it is a property of the model rather than of a turn.
     */
    fun tokenCount(text: String): Int

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
    fun generate(prompt: String, jsonSchema: String?): String

    /**
     * Stream the reply, one chunk per emission.
     *
     * The Apple leg pulls from the facade, which buffers what the runtime pushes from its own
     * thread; Android collects the SDK's own flow. Both hand back chunks and nothing else —
     * whoever is timing decides when each one arrived.
     */
    fun stream(prompt: String, jsonSchema: String?): Flow<String>

    fun close()
}

/** The binding this target was compiled with. */
internal expect fun platformBridge(): LiteRtLmBridge

internal data class EngineOptions(
    val modelPath: String,
    val cacheDir: String? = null,
    val accelerator: Accelerator = Accelerator.CPU,
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
    /**
     * Take the most likely token every step, ignoring [topK], [topP] and [temperature].
     *
     * Decided here rather than by each leg, because LiteRT-LM's sampler type is fixed when the
     * conversation opens and temperature 0 does *not* select it: a top-p sampler asked for
     * temperature 0 still samples, and produces a different answer to the same question. The
     * Apple leg maps this onto the runtime's greedy sampler; Android has no sampler type in its
     * public config, so it uses top-k of 1, which is argmax by another name.
     */
    val greedy: Boolean = false,
)

// Both legs need concrete numbers — Android's SamplerConfig has no default for the first
// three — so the defaults live here rather than being read from one leg and copied into the
// other. koilm_default_session_params() returns exactly these; SessionDefaultsTest fails if
// the facade ever drifts from them.
/** Sentinel the facade reads as "leave the runtime's own seeding alone". */
internal const val UNSEEDED = -1

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
    // Temperature 0 means "no randomness" to a caller, and on llama.cpp it genuinely is
    // greedy. Making it mean the same thing here is what lets one sampling configuration be
    // described as identical across backends.
    greedy = (temperature ?: -1.0) == 0.0,
)
