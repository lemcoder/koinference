package io.github.lemcoder.koinference.llamacpp.internal

import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.Accelerator
import kotlinx.coroutines.flow.Flow

/**
 * The seam between the common runtime and whichever llama.cpp binding a target has.
 *
 * Same shape as `:backends:litertlm`'s bridge, on purpose — see `docs/backends.md`. Interfaces
 * rather than top-level `expect fun`s so that everything in
 * [io.github.lemcoder.koinference.llamacpp.LlamaCppRuntime] can be exercised without a model:
 * an `expect` declaration can only be produced by a platform, so with one at the seam the
 * session rebuild, the reload on a backend change and the unload-during-generation race are all
 * unreachable from a test.
 *
 * The nesting matches the lifetime nesting of the things themselves — a bridge opens models, a
 * model opens sessions — so a handle cannot be used without the thing that owns it.
 *
 * Every function here throws on failure; callers do not check for null or zero.
 */
internal interface LlamaCppBridge {

    fun openModel(options: ModelOptions): LlamaCppModel

    /**
     * Convert a JSON schema to the GBNF grammar a session takes.
     *
     * On the bridge rather than the session: llama.cpp's converter needs no model, and a caller
     * asking for a grammar has not necessarily loaded one yet.
     *
     * @throws IllegalArgumentException if the schema does not parse or convert.
     */
    fun jsonSchemaToGrammar(schema: String): String
}
/** The binding this target was compiled with. */
internal expect fun platformBridge(): LlamaCppBridge
// Concrete numbers rather than a sentinel, so the common runtime can report what a session was
// actually created with. These mirror koi_default_session_params(); SessionDefaultsTest fails if
// the facade ever drifts from them.
internal const val DEFAULT_TEMPERATURE = 0.8f
internal const val DEFAULT_TOP_K = 40
internal const val DEFAULT_MIN_P = 0.05f
/**
 * Offload everything the model has. llama.cpp clamps to the layer count, so a number larger than
 * any real model is how "all of it" is spelled.
 */
internal const val ALL_GPU_LAYERS = 999
/**
 * Map the common sampling knobs onto a session.
 *
 * [GenerationParameters.topP] and [GenerationParameters.seed] are dropped rather than
 * substituted: `koi_session_create` takes neither, and passing min-p where top-p was asked for
 * would make a caller's explicit setting mean something it did not ask for. The backend
 * documents which knobs it ignores; it does not reinterpret them.
 */
internal fun GenerationParameters.toSessionOptions(
    nCtx: Int,
    nThreads: Int,
    nPredict: Int,
): SessionOptions = SessionOptions(
    nCtx = nCtx,
    nThreads = nThreads,
    nPredict = nPredict,
    temperature = temperature?.toFloat() ?: DEFAULT_TEMPERATURE,
    topK = topK ?: DEFAULT_TOP_K,
    minP = minP?.toFloat() ?: DEFAULT_MIN_P,
)
