package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.runtime.KoinferenceException
import java.io.File

/**
 * Refuse [modelPath] with a [KoinferenceException.LoadFailed] when the device plainly cannot hold
 * it, rather than let a backend's native load trip an OOM or the runtime get SIGKILL'd mid-mmap
 * under lmkd — which reads like a hang in the loader.
 *
 * **Android only, on purpose, which is why it lives in androidMain and not commonMain.** Android is
 * the one platform that faces lmkd; a desktop has swap. The loading backends share this one copy
 * from their own androidMain rather than each carrying the arithmetic and the message.
 *
 * A floor, not a predictor: the peak is estimated from the file size — a precise one needs the
 * model's dimensions and differs per backend (LiteRT-LM's XNNPACK cache alone reached 3.4 GB) — and
 * it leans low, since a false refusal of a model that would have run is worse than a missed marginal
 * OOM. The estimate is anchored on a measurement: LFM2.5-1.2B Q5_K_M is 843 MB on disk and peaked
 * at 1.68 GB PSS at a 2048-token context.
 *
 * It does **not** model the dynamic lmkd kill under *global* memory pressure — a model that fits at
 * load and is killed minutes later — which is an `onTrimMemory`/PSI reaction, not a static check.
 *
 * A no-op when `/proc/meminfo` carries no `MemAvailable` line or the file cannot be stat'd.
 */
fun requireModelFits(modelPath: String) {
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
