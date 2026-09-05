package io.github.lemcoder.koinference.benchmark.app.ui

/**
 * One line of the results: an engine against a workload.
 *
 * Memory sits beside the timings rather than behind a scroll, because on a phone it is often the
 * number that decides whether a model ships at all — llama.cpp and Cera answered the same prompts
 * from the same weights at 2851 MB and 741 MB of peak PSS.
 */
data class BenchmarkRow(
    val engineId: String,
    val workload: String,
    val status: String,
    val tokensPerSecond: Double?,
    val ttftMs: Double?,
    val tokens: Int?,
    val chunks: Int?,
    /** Highest PSS seen across the run, sampled in the engine's own process. */
    val peakPssMb: Double?,
    /**
     * What loading the weights added to PSS: after-load minus before-init.
     *
     * Not "the size of the model", and the difference matters: an engine that mmaps its weights
     * pages them in as it decodes, so it can report a small load delta and a large peak. Measured
     * on a Pixel 8a with the same GGUF, llama.cpp adds 1339 MB at load and peaks at 2861 MB, while
     * Cera adds 50 MB and peaks at 717 MB. [peakPssMb] is the number to compare engines on.
     */
    val weightsPssMb: Double?,
    /**
     * PSS once the run finished and the model was released.
     *
     * Next to [peakPssMb] it says whether the engine gave the memory back. RSS is deliberately not
     * shown: the harness records it from the final snapshot only, so beside a peak taken mid-run it
     * reads as a 104 MB process that peaked at 2861 MB.
     */
    val afterRunPssMb: Double?,
    val note: String?,
    /** Whether [note] is a failure rather than one of the harness's standing remarks. */
    val noteIsFailure: Boolean,
)
