package io.github.lemcoder.koinference.llamacpp.internal

import android.os.Build
import java.io.File

/**
 * Two floors, independent of each other: the AAR's `minSdk 31`, reachable only through a consumer
 * that overrode it, and the dot-product extension the kernels are compiled to require.
 */
internal actual fun llamaCppUnsupportedReason(): String? {
    if (Build.VERSION.SDK_INT < MIN_SDK) {
        return "Android ${Build.VERSION.SDK_INT}; this backend is built for API $MIN_SDK and up"
    }
    if (!hasDotProduct()) {
        return "the CPU has no ARM dot-product extension (asimddp), which ggml's Q4_0 kernels are " +
            "compiled to require"
    }
    return null
}

private const val MIN_SDK = 31

/** Whether any core reports `asimddp`. Any, not all: `/proc/cpuinfo` has a Features line per core. */
private fun hasDotProduct(): Boolean =
    runCatching { File("/proc/cpuinfo").readText() }
        .getOrDefault("")
        .lineSequence()
        .filter { it.startsWith("Features") }
        .any { "asimddp" in it.split(':').last().split(' ') }
