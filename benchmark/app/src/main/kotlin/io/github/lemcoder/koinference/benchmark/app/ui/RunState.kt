package io.github.lemcoder.koinference.benchmark.app.ui

/** Where a benchmark run has got to. */
sealed interface RunState {

    data object Idle : RunState

    /** [line] is the newest progress line from whichever engine is running. */
    data class Running(val line: String) : RunState

    data class Finished(
        val rows: List<BenchmarkRow>,
        val resultsJson: List<String>,
        /** Engines that failed, if any; a run of two engines can half-succeed. */
        val failures: List<String>,
    ) : RunState

    data class Failed(val message: String) : RunState
}
