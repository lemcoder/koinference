package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.runtime.ModelRuntime
import io.github.lemcoder.koinference.runtime.StreamingTextRuntime
import io.github.lemcoder.koinference.runtime.TextRuntime
import io.github.lemcoder.koinference.runtime.TokenCounting

/**
 * What a loaded GGUF model can do.
 *
 * Nothing beyond what the composed interfaces already promise, which is the point: the parameter
 * and settings members that used to be declared here are on [ModelRuntime] now, identical to the
 * ones LiteRT-LM had. The name survives because [LlamaCppModelLoader.load] returns it, and because
 * a caller who wants this engine specifically should be able to say so.
 *
 * Text only. There was an embedding counterpart with no implementation behind it; it is gone, along
 * with the sealed parent that held the two apart and the downcast every caller needed as a result.
 * `koi_embed` is still in the facade — see `docs/backends.md` for why a C function outlives its
 * Kotlin surface.
 */
interface LlamaCppTextRuntime : ModelRuntime, TextRuntime, StreamingTextRuntime, TokenCounting
