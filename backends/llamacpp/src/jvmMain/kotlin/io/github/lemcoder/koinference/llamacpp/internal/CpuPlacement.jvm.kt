package io.github.lemcoder.koinference.llamacpp.internal

import java.io.File

/**
 * JVM: the topology rule, over the real /proc and /sys.
 *
 * The JVM runs on Linux and on macOS and cannot tell which at compile time, so it uses the rule
 * either way. On a macOS JVM the files simply are not there and the policy answers "do not pin" —
 * the same answer the macOS native leg gives outright. On Linux it behaves as the Android leg does.
 *
 * Duplicated verbatim between jvmMain and androidMain rather than shared through an intermediate
 * source set; see docs/backends.md for why this repo does not add one. The file name says which
 * `expect` it answers and for which platform, which `ActualFileNamingTest` enforces.
 */
internal actual fun platformCpuPlacement(): CpuPlacementSource = CpuPlacementPolicy(JvmSystemFiles)
