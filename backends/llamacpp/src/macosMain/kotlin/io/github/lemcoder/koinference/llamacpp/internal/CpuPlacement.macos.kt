package io.github.lemcoder.koinference.llamacpp.internal


/**
 * macOS: no pinning, `cores - 2` workers.
 *
 * **No pinning because macOS does not offer it.** ggml's `ggml_thread_apply_affinity` is a no-op on
 * Apple platforms — it discards the mask and returns success — so passing one would look like it
 * worked. Thread priority is implemented there and affinity is not, so priority is the lever if one
 * is ever wanted.
 *
 * **`cores - 2`, measured.** On an M4 (4 performance + 6 efficiency cores) running LFM2.5-1.2B
 * Q4_0: 4 threads 102 tok/s, 5 gave 120, 7 gave 139, 8 gave 144, 9 gave 135, 10 gave 123. Eight is
 * `cores - 2`.
 *
 * Note that the performance-core count, 4, is one of the *worst* answers here. macOS schedules
 * heterogeneous cores well on its own, so the efficiency cores contribute rather than stalling a
 * barrier — the opposite of Android, where an unpinned thread on a little core halves throughput.
 */
internal actual fun platformCpuPlacement(): CpuPlacementSource = CpuPlacementSource {
    CpuPlacement.unpinned(threads = onlineCores().minus(2).coerceIn(2, MAX_DECODE_THREADS))
}
