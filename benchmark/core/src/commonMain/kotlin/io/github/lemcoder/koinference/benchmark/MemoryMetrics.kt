package io.github.lemcoder.koinference.benchmark

import kotlinx.serialization.Serializable

/**
 * Process memory around the run.
 *
 * Java heap alone would be close to meaningless here: llama.cpp holds the weights in native
 * memory, so a comparison against LiteRT-LM has to be made on PSS or RSS.
 */
@Serializable
data class MemoryMetrics(
    val beforeInitPssKb: Long? = null,
    val afterLoadPssKb: Long? = null,
    val afterWarmupPssKb: Long? = null,
    val peakPssKb: Long? = null,
    val afterRunPssKb: Long? = null,
    val nativeHeapKb: Long? = null,
    val javaHeapKb: Long? = null,
    val rssKb: Long? = null,
)
