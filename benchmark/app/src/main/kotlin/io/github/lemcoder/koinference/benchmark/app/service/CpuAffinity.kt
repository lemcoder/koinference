package io.github.lemcoder.koinference.benchmark.app.service

import android.os.Process
import java.io.File

/**
 * Restricts this process to a set of CPUs, without native code.
 *
 * Why it exists: an engine that spawns its own workers decides how many, but not where they run.
 * llama.cpp takes a CPU mask through its facade and gained 6.5x on this device from it; ExecuTorch
 * and Cera expose no such knob and were measured spreading eight threads across a big.LITTLE phone,
 * little cores included. Affinity is the one lever that reaches all three, because it belongs to the
 * process rather than the engine.
 *
 * **`taskset`, not JNI, and that is a deliberate trade.** Android has no affinity API in Java and
 * `sched_setaffinity` needs native code, which would mean a native build in a module that otherwise
 * has none. Setting affinity on one's own process needs no privilege, so `taskset -ap` should work —
 * "should" being why [apply] reports what happened rather than assuming.
 *
 * Threads inherit the mask from their creator, so this has to run *before* the engine builds its
 * pool. After that it would leave the existing workers where they are.
 */
object CpuAffinity {

    /** What happened, for a log line: this is exactly the kind of thing that fails quietly. */
    data class Outcome(val applied: Boolean, val detail: String)

    /**
     * Pins every thread of this process to [mask], a CPU bitmask in hex without the `0x`.
     *
     * Thread by thread, deliberately. `taskset -ap <pid>` is the obvious call and it fails on
     * Android: the child process it spawns cannot read `/proc/<pid>/task/` of another process under
     * `hidepid`, even at the same uid — "No such file or directory", which reads like the process is
     * gone rather than unreadable. `/proc/self/task` is readable, so this enumerates there and pins
     * each TID, which needs no `/proc` access from the child at all.
     *
     * Threads made later inherit from whichever thread made them, so pinning all of the current ones
     * covers the pool an engine builds afterwards.
     */
    fun apply(mask: String): Outcome {
        val threads = runCatching {
            File("/proc/self/task").list()?.toList().orEmpty()
        }.getOrDefault(emptyList())

        if (threads.isEmpty()) return Outcome(false, "cannot read /proc/self/task")

        var pinned = 0
        var lastFailure = ""
        threads.forEach { tid ->
            val outcome = pin(mask, tid)
            if (outcome.applied) pinned++ else lastFailure = outcome.detail
        }

        return if (pinned > 0) {
            Outcome(true, "pinned $pinned/${threads.size} threads to $mask (now ${current()})")
        } else {
            Outcome(false, "no thread could be pinned: $lastFailure")
        }
    }

    private fun pin(mask: String, tid: String): Outcome = try {
        val process = ProcessBuilder("taskset", "-p", mask, tid)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) Outcome(true, output) else Outcome(false, output)
    } catch (failure: Exception) {
        Outcome(false, "${failure::class.java.simpleName}: ${failure.message}")
    }

    /** The mask the kernel says this process has, read back rather than trusted. */
    fun current(): String = runCatching {
        File("/proc/self/status").readLines()
            .first { it.startsWith("Cpus_allowed_list:") }
            .substringAfter(':')
            .trim()
    }.getOrDefault("unknown")

    /**
     * The CPUs at the highest two frequency tiers, as a hex mask.
     *
     * A Pixel 8a is 4x1.70GHz + 4x2.37GHz + 1x2.91GHz, so this is cpus 4-8. Read from sysfs rather
     * than hardcoded: the same policy `:backends:llamacpp` applies natively, in the one place here
     * that can apply it to an engine that offers no knob of its own.
     */
    fun bigCoreMask(): String? {
        val frequencies = (0 until MAX_CPUS).mapNotNull { cpu ->
            val file = File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
            runCatching { file.readText().trim().toLong() }.getOrNull()?.let { cpu to it }
        }
        if (frequencies.isEmpty()) return null

        val slowest = frequencies.minOf { it.second }
        val fast = frequencies.filter { it.second > slowest }.map { it.first }
        if (fast.isEmpty()) return null

        return fast.fold(0L) { mask, cpu -> mask or (1L shl cpu) }.toString(16)
    }

    private const val MAX_CPUS = 32
}
