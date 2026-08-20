package io.github.lemcoder.koinference.benchmark

import kotlinx.serialization.Serializable

/** Identifies the code that produced a result, so a number can be traced back to a build. */
@Serializable
data class BuildInfo(
    val appVersion: String? = null,
    val gitCommit: String? = null,
)
