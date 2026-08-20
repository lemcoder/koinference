package io.github.lemcoder.koinference.llamacpp.internal

import android.os.Build
import java.io.File

/**
 * Two floors, and they are independent.
 *
 * The AAR declares `minSdk 31`, so the version check is only reachable through a consumer that
 * overrode it — worth keeping for exactly that case, and cheap.
 *
 * The one that does the work is the CPU check. **API level does not imply dotprod**: Cortex-A53 and
 * A55 class arm64 parts ship on current Android versions, so a device can be well past 31 and still
 * have no `asimddp`. Read from `/proc/cpuinfo` rather than through JNI because it is the same file
 * `CpuPlacement` already reads, and because a rule that can be Kotlin is Kotlin.
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

/**
 * Whether any core reports `asimddp`.
 *
 * Any, not all: `/proc/cpuinfo` lists a Features line per core, and the answer is a property of the
 * SoC — a big.LITTLE device does not mix cores of different ISA levels, and if one did, ggml's
 * threads would migrate onto the wrong one anyway.
 */
private fun hasDotProduct(): Boolean =
    runCatching { File("/proc/cpuinfo").readText() }
        .getOrDefault("")
        .lineSequence()
        .filter { it.startsWith("Features") }
        .any { "asimddp" in it.split(':').last().split(' ') }
