package io.github.lemcoder.koinference.llamacpp.internal

/**
 * The native targets do not place threads by hand.
 *
 * On Darwin there is nothing to place with: no /proc or /sys to read a topology from, and no
 * equivalent of sched_setaffinity — `thread_policy_set` affinity tags are advisory and ignored on
 * Apple silicon. linuxX64 could in principle do what Android does, but it is a desktop target where
 * the scheduler is not fighting a little cluster, and carrying the heuristic here would mean a third
 * copy of it. Android is where this matters and Android is where it lives.
 */
internal actual fun platformCpuPlacement(): CpuPlacementSource = CpuPlacementSource {
    CpuPlacement.UNPINNED
}
