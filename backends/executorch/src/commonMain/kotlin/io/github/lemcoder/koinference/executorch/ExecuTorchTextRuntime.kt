package io.github.lemcoder.koinference.executorch

import io.github.lemcoder.koinference.runtime.GeneratingRuntime
import io.github.lemcoder.koinference.runtime.text.TokenCounting

/**
 * What a caller holding an ExecuTorch model can do with it.
 *
 * [TokenCounting] with a narrower reach than the other backends': see
 * [ExecuTorchRuntime.countTokens]. It answers for the reply the engine last produced, which is the
 * only text the harness ever asks about, and refuses anything else rather than guessing.
 */
interface ExecuTorchTextRuntime : GeneratingRuntime, TokenCounting
