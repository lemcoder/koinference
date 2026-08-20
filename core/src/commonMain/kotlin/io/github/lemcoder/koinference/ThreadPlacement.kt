package io.github.lemcoder.koinference

/**
 * A runtime that can say — and change — which CPUs it computes on.
 *
 * Optional, like [TokenCounting]: an engine that manages its own threads and exposes no control
 * over placement simply does not implement it.
 *
 * Placement is not a detail. On a big.LITTLE phone a compute thread scheduled onto a little core
 * makes every barrier wait for it, and one misplaced worker can halve throughput — so a caller
 * that knows more than the engine does (that the app just went to the background, that the device
 * is throttling) needs a way to say so.
 */
interface ThreadPlacement {

    /**
     * CPUs the compute threads are currently pinned to, ascending. Empty means the platform's
     * default placement, which is also what a platform exposing no topology reports.
     */
    suspend fun pinnedCpus(): List<Int>

    /**
     * Pin the compute threads to [cpus], or pass an empty list to return to default placement.
     *
     * Suspends because it rebuilds the thread pool, and doing that under a running generation
     * would pull the workers out from beneath it. CPUs this process may not use are dropped
     * rather than honoured — an app's cpuset is not the SoC's topology.
     */
    suspend fun pinToCpus(cpus: List<Int>)
}
