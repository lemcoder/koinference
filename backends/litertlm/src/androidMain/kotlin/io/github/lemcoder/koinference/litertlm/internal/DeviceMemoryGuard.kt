package io.github.lemcoder.koinference.litertlm.internal

import io.github.lemcoder.koinference.runtime.KoinferenceException
import java.io.File

/**
 * Coarse, Android-only load guard: refuse a model this device plainly cannot hold rather than let
 * the native load trip an OOM or the runtime get SIGKILL'd mid-mmap under lmkd. Android is the only
 * leg that needs it — a desktop has swap; this is mobile memory pressure — so it lives only here,
 * like the CPU-support check, and the other legs have nothing to declare.
 *
 * A floor, not a predictor: the peak is estimated from the file size and undershoots on purpose (a
 * false refusal of a runnable model is worse than a missed marginal OOM). It does not model the
 * dynamic lmkd kill under global memory pressure — a model that fits at load and is killed minutes
 * later — only the never-fits case; that dynamic kill is an onTrimMemory/PSI concern.
 */
internal fun requireModelFits(modelPath: String) {
    val available = runCatching { File("/proc/meminfo").readText() }.getOrNull()
        ?.let { memAvailableBytes(it) } ?: return
    val size = File(modelPath).takeIf { it.isFile }?.length() ?: return
    val peak = size + maxOf(size / 2, 512L * 1024 * 1024)
    val short = peak - available
    if (short <= 0) return
    val mib = { bytes: Long -> bytes / (1024 * 1024) }
    throw KoinferenceException.LoadFailed(
        "$modelPath needs ~${mib(peak)} MiB but only ~${mib(available)} MiB is available on this " +
            "device (${mib(short)} MiB short); load a smaller model or a lower quantization",
    )
}

/** `/proc/meminfo`'s `MemAvailable` line, in bytes, or null when the text has no such line. */
private fun memAvailableBytes(meminfo: String): Long? =
    meminfo.lineSequence().firstOrNull { it.startsWith("MemAvailable:") }
        ?.let { Regex("""\d+""").find(it)?.value?.toLongOrNull() }
        ?.times(1024)
