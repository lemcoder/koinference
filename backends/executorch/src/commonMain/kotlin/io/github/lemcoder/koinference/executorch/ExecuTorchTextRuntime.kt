package io.github.lemcoder.koinference.executorch

import io.github.lemcoder.koinference.runtime.GeneratingRuntime

/**
 * What a caller holding an ExecuTorch model can do with it.
 *
 * No `TokenCounting`, unlike the other three: the AAR exposes no tokenizer, only a path it hands to
 * native code. A backend that cannot count is better than one that counts with somebody else's
 * vocabulary, so the harness reports no token figures for this engine and its chunk counts stand
 * alone — see `docs/backends.md`.
 */
interface ExecuTorchTextRuntime : GeneratingRuntime
