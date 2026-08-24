@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.llamacpp.internal

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen

/**
 * Linux: the same topology rule Android uses.
 *
 * Unlike Darwin, Linux honours `sched_setaffinity` and describes its CPUs through /proc and /sys,
 * so the rule that works on Android applies unchanged — group the usable cores, drop the slowest
 * group, pin to the largest of the rest.
 *
 * On a desktop with one kind of core the policy finds a single tier and declines to pin, which is
 * the right answer there: nothing to avoid. It earns its keep on the machines that do have a split,
 * which now includes plenty of ARM servers and laptops.
 *
 * Unmeasured — no Linux machine has run this — but it is the measured Android rule rather than a
 * new guess, and it self-disables where there is no split to act on.
 */
internal actual fun platformCpuPlacement(): CpuPlacementSource = CpuPlacementPolicy(PosixSystemFiles)
/** /proc/cpuinfo has the longest lines here, well inside this. */
internal const val LINE_BYTES = 512
