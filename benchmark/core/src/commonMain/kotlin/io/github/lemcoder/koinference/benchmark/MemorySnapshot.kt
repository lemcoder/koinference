package io.github.lemcoder.koinference.benchmark

/** One memory reading. Fields the platform does not expose stay null. */
data class MemorySnapshot(
    val pssKb: Long? = null,
    val rssKb: Long? = null,
    val nativeHeapKb: Long? = null,
    val javaHeapKb: Long? = null,
)
