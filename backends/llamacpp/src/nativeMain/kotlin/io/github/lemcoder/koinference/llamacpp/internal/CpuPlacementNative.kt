@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.llamacpp.internal

import platform.posix._SC_NPROCESSORS_ONLN
import platform.posix.sysconf

/**
 * Placement on the native targets: no pinning, `cores - 2` workers.
 *
 * **No pinning, because it is not available.** ggml's `ggml_thread_apply_affinity` is a documented
 * no-op on Apple platforms — it discards the mask and returns success — so a Darwin build that
 * passed one would look like it had worked. Thread priority is implemented there and affinity is
 * not, so priority is the lever if one is ever wanted. linuxX64 could pin, but it is a desktop
 * target that is not fighting a little cluster, and the Android rule would be a second copy of
 * itself here.
 *
 * **`cores - 2` workers, measured.** On an M4 (4 performance + 6 efficiency cores) running
 * LFM2.5-1.2B Q4_0: 4 threads 102 tok/s, 5 gave 120, 7 gave 139, 8 gave 144, 9 gave 135, 10 gave
 * 123. Eight is `cores - 2`.
 *
 * Deliberately the opposite of Android's answer, which is one worker per big core — 4 on a Pixel
 * 8a, where 8 ran at half that. The difference is pinning: Darwin's scheduler places heterogeneous
 * cores well on its own, so the efficiency cores contribute, where on Android the same spread
 * across a little cluster stalled every barrier until the threads were pinned to the big one.
 */
internal actual fun platformCpuPlacement(): CpuPlacementSource = CpuPlacementSource {
    val cores = sysconf(_SC_NPROCESSORS_ONLN).toInt().coerceAtLeast(1)
    CpuPlacement.unpinned(threads = (cores - 2).coerceIn(2, MAX_DECODE_THREADS))
}

/** Mirrors the facade's own ceiling; above this, more workers stopped helping on every machine tried. */
private const val MAX_DECODE_THREADS = 8
