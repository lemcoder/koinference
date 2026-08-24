package io.github.lemcoder.koinference.llamacpp.internal

/**
 * Which CPUs to decode on, and how many workers to run.
 *
 * Both halves are one decision and both are platform-specific, which is why they travel together.
 * [cpus] empty means "do not pin" — either the platform cannot (Darwin ignores affinity outright)
 * or nothing survived the intersection with what this process may use. A [threads] of 0 means even
 * the count is left to the facade, which is a last resort rather than a normal answer.
 *
 * The two platforms want opposite counts, measured: Android wants one worker per big core (4 on a
 * Pixel 8a, where 8 ran at half the speed) and macOS wants `cores - 2` (8 on an M4, where 4 ran at
 * 70%). Nothing here reconciles them because there is nothing to reconcile — a pinned big cluster
 * and an unpinned heterogeneous machine are different problems.
 */
internal data class CpuPlacement(
    val cpus: List<Int> = emptyList(),
    val threads: Int = 0,
) {
    val pinned: Boolean get() = cpus.isNotEmpty()

    companion object {
        /** No pinning and no opinion on the count. */
        val UNPINNED = CpuPlacement()

        /** No pinning, but a measured worker count — the shape every non-Linux platform gives. */
        fun unpinned(threads: Int) = CpuPlacement(cpus = emptyList(), threads = threads)
    }
}
/**
 * How this platform decides where to put decode threads.
 *
 * An `expect` rather than a shared default, because whether placement is a question at all is
 * platform-specific. Android is where it matters — a decode thread landing on a little core makes
 * every barrier wait for it — and the ART legs answer with [CpuPlacementPolicy]. The native targets
 * answer that there is nothing to place: Darwin has no /proc or /sys to read a topology from and no
 * equivalent of `sched_setaffinity`, and linuxX64 is a desktop target not fighting a little cluster.
 *
 * The rule itself stays in common code even though only one leg activates it: it is pure given
 * [SystemFiles], and keeping it here is what lets its topology cases be tested everywhere rather
 * than only where it runs.
 */
internal expect fun platformCpuPlacement(): CpuPlacementSource
/** Parses the `0-3,5,8-9` form both /proc and /sys use for CPU sets. */
internal fun parseCpuList(spec: String): List<Int> =
    spec.trim().split(',').flatMap { part ->
        val range = part.trim()
        if (range.isEmpty()) return@flatMap emptyList()
        val bounds = range.split('-')
        val first = bounds.first().toIntOrNull() ?: return@flatMap emptyList()
        val last = if (bounds.size > 1) bounds[1].toIntOrNull() ?: first else first
        if (last < first) emptyList() else (first..last).toList()
    }
