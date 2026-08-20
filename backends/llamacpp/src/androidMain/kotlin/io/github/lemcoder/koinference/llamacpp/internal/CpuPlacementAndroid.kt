package io.github.lemcoder.koinference.llamacpp.internal

import java.io.File

/**
 * Android: the topology rule, over the real /proc and /sys.
 *
 * This is the platform the rule was measured on. A Pixel 8a runs LFM2.5-1.2B Q4_0 at 5-6 tok/s
 * with four unpinned threads and 34-40 with the same four pinned to its A715 cluster, because an
 * unpinned worker landing on an A510 makes every barrier wait for it.
 *
 * Duplicated verbatim between jvmMain and androidMain rather than shared through an intermediate
 * source set; see docs/backends.md for why this repo does not add one.
 */
internal actual fun platformCpuPlacement(): CpuPlacementSource = CpuPlacementPolicy(JvmSystemFiles)

private object JvmSystemFiles : SystemFiles {
    // readText, not readBytes: /proc and /sys report a size of zero, so anything that trusts the
    // file length reads nothing.
    override fun read(path: String): String? = runCatching { File(path).readText() }.getOrNull()
}
