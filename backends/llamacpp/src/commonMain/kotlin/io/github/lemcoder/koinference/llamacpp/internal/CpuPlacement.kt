package io.github.lemcoder.koinference.llamacpp.internal

/**
 * Which CPUs to decode on, and how many workers to run.
 *
 * [cpus] empty means "do not pin" — the platform exposed nothing to act on, or nothing survived
 * the intersection with what this process may use. [threads] is 0 in that case, meaning the facade
 * picks.
 */
internal data class CpuPlacement(
    val cpus: List<Int> = emptyList(),
    val threads: Int = 0,
) {
    val pinned: Boolean get() = cpus.isNotEmpty()

    companion object {
        val UNPINNED = CpuPlacement()
    }
}

/**
 * The small text files Linux describes its CPUs through.
 *
 * An interface so the policy above it is testable against topologies nobody here owns — a
 * three-cluster SoC, a machine whose cores all clock the same, an app confined to the little
 * cluster. Returns null for a file that does not exist, which is the normal case off Linux.
 */
internal interface SystemFiles {
    fun read(path: String): String?
}

/** The files of the machine this is running on. */
internal expect fun platformSystemFiles(): SystemFiles

/**
 * Chooses the CPUs to pin decode threads to.
 *
 * This is the whole heuristic, in one place, in Kotlin, so it can be tested. It used to be C
 * inside the facade, where the only way to find out what it decided was to infer it from
 * throughput.
 *
 * The rule: take the cores this process may actually use, group them by capability, drop the
 * slowest group, and pin to the largest group that remains — the biggest set of cores that reach
 * a barrier together, which is what ggml rewards. Then run one worker per core in that group.
 *
 * Why not simply "all the big cores": on a Pixel 8a that is 4 cores of A715 plus one X3, and
 * including the X3 measured *worse* — one core 23% faster than four others still waits at the same
 * barrier. Why not "half the cores", which an earlier version of this used: that was compensating
 * for the threads drifting onto little cores, which pinning fixes properly. Pinned, 4 threads run
 * at 34-40 tok/s where unpinned they ran at 5-6.
 */
internal class CpuPlacementPolicy(private val files: SystemFiles = platformSystemFiles()) {

    fun choose(): CpuPlacement {
        val usable = usableCpus()
        // One core, or nothing readable: there is no placement decision to make.
        if (usable.size < 2) return CpuPlacement.UNPINNED

        // Peak frequency first. It is the signal that matches what a barrier cares about, but it
        // does not always separate clusters — some SoCs clock a big and a little core to the same
        // ceiling — so microarchitecture is the fallback.
        val groups = groupByFrequency(usable).takeIf { it.size >= 2 }
            ?: groupByMicroarchitecture(usable).takeIf { it.size >= 2 }
            ?: return CpuPlacement.UNPINNED

        // Groups are ordered slowest-first, so drop the head and take the largest of the rest.
        val candidates = groups.drop(1)
        val best = candidates.maxByOrNull { it.size } ?: return CpuPlacement.UNPINNED
        return CpuPlacement(cpus = best.sorted(), threads = best.size)
    }

    /**
     * Cores this process may run on that are also online.
     *
     * Both halves matter, and neither is the SoC's topology. Android moves an app between
     * cpusets — `foreground` is typically every core but the prime one, `background` is the little
     * cluster — so a mask built from the hardware alone can name cores this process is forbidden
     * from touching, and pinning to one of those fails rather than degrading. Cores also go
     * offline under thermal pressure.
     */
    private fun usableCpus(): List<Int> {
        val permitted = files.read(STATUS)
            ?.lineSequence()
            ?.firstOrNull { it.startsWith("Cpus_allowed_list:") }
            ?.substringAfter(':')
            ?.let(::parseCpuList)
            .orEmpty()

        val online = files.read(ONLINE)?.let(::parseCpuList).orEmpty()

        return when {
            permitted.isEmpty() -> online
            online.isEmpty() -> permitted
            else -> permitted.filter { it in online.toSet() }
        }
    }

    private fun groupByFrequency(usable: List<Int>): List<List<Int>> =
        usable.mapNotNull { cpu ->
            files.read(maxFrequencyPath(cpu))?.trim()?.toLongOrNull()?.let { cpu to it }
        }.groupBy({ it.second }, { it.first })
            .entries
            // Sorted explicitly: toSortedMap is JVM-only, and the order is the whole point — the
            // caller drops the first group as the slowest.
            .sortedBy { it.key }
            .map { it.value }

    /**
     * Grouped by the `CPU part` field of /proc/cpuinfo — a core's microarchitecture id.
     *
     * Ordered by id, which is not an ordering by speed. It does not need to be: the caller drops
     * the first group, and when frequencies tie the little cores are the ones with the lower part
     * id on every ARM design this has been checked against (A510 `0xd46` below A715 `0xd4d` below
     * X3 `0xd4e`).
     */
    private fun groupByMicroarchitecture(usable: List<Int>): List<List<Int>> {
        val text = files.read(CPUINFO) ?: return emptyList()
        val parts = mutableMapOf<Int, String>()
        var current = -1
        text.lineSequence().forEach { line ->
            when {
                line.startsWith("processor") ->
                    current = line.substringAfter(':').trim().toIntOrNull() ?: -1

                line.startsWith("CPU part") && current >= 0 ->
                    parts[current] = line.substringAfter(':').trim()
            }
        }
        return usable.mapNotNull { cpu -> parts[cpu]?.let { cpu to it } }
            .groupBy({ it.second }, { it.first })
            .entries
            .sortedBy { it.key }
            .map { it.value }
    }

    private companion object {
        const val STATUS = "/proc/self/status"
        const val ONLINE = "/sys/devices/system/cpu/online"
        const val CPUINFO = "/proc/cpuinfo"

        fun maxFrequencyPath(cpu: Int) =
            "/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq"
    }
}

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
