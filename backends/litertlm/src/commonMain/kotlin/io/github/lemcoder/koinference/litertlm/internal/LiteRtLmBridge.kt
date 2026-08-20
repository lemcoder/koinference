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
/** The binding this target was compiled with. */
internal expect fun platformBridge(): LiteRtLmBridge
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
