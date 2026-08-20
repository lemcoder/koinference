@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.llamacpp.internal

import platform.posix._SC_NPROCESSORS_ONLN
import platform.posix.sysconf

/** Cores currently online, straight from libc — no C of ours involved. */
internal fun onlineCores(): Int = sysconf(_SC_NPROCESSORS_ONLN).toInt().coerceAtLeast(1)

/** Above this, more workers stopped helping on every machine tried. Mirrors the facade's ceiling. */
internal const val MAX_DECODE_THREADS = 8
