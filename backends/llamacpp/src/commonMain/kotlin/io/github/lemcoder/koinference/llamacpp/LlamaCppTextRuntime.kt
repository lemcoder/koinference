package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.runtime.GeneratingRuntime
import io.github.lemcoder.koinference.runtime.text.TokenCounting

/**
 * What a loaded GGUF model can do.
 *
 * Nothing beyond generating and counting tokens, which is the point: the settings members are on
 * `ModelRuntime` and the reply shape is `ResponsePart`, both shared with every other backend. The
 * name survives because [LlamaCppModelLoader.load] returns it, and because a caller who wants this
 * engine specifically should be able to say so.
 *
 * This engine only ever emits `ResponsePart.Text`. That is a fact about the engine rather than about
 * the interface — a model that interleaved audio would implement exactly this.
 */
interface LlamaCppTextRuntime : GeneratingRuntime, TokenCounting
