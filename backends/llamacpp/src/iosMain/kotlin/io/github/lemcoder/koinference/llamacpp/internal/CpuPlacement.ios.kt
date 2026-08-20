package io.github.lemcoder.koinference.llamacpp.internal

/**
 * iOS: no pinning, `cores - 2` workers.
 *
 * Same reasoning as macOS — affinity is a no-op on Apple platforms — and the same rule, but
 * **unmeasured**. No iOS device has run this. An iPhone's split is narrower than a Mac's (2
 * performance cores against 4 efficiency on recent A-series, where the M4 has 4 against 6), so
 * `cores - 2` may well not be the best answer here even though it is on macOS.
 *
 * Kept separate from the macOS leg rather than shared through appleMain precisely so that it can be
 * changed when somebody measures it, without touching a platform where the number is known.
 */
internal actual fun platformCpuPlacement(): CpuPlacementSource = CpuPlacementSource {
    CpuPlacement.unpinned(threads = onlineCores().minus(2).coerceIn(2, MAX_DECODE_THREADS))
}
