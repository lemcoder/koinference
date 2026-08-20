package io.github.lemcoder.koinference.benchmark.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the inference process is costing.
 *
 * Not an OpenAI endpoint — `/koinference/memory` — and the reason the service runs in its own
 * process at all: read from inside that process, these numbers describe the model and the
 * engine, with no Activity, no HTTP stack and no test runner mixed in.
 */
@Serializable
data class ProcessMemory(
    val pid: Int,
    val processName: String,
    val pssKb: Long?,
    val rssKb: Long?,
    val nativeHeapKb: Long?,
    val javaHeapKb: Long?,
    val engine: String?,
    val modelPath: String?,
    val modelLoadMs: Double?,
)
