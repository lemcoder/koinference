package io.github.lemcoder.koinference.cera

import io.github.lemcoder.koinference.runtime.GeneratingRuntime
import io.github.lemcoder.koinference.runtime.text.TokenCounting

/**
 * What a caller holding a Cera model can do with it.
 *
 * The public shape of this backend: generation from [GeneratingRuntime], token counting from
 * [TokenCounting], and nothing of its own — Cera's session carries no state a caller has to reset,
 * unlike LiteRT-LM's conversation.
 */
interface CeraTextRuntime : GeneratingRuntime, TokenCounting
