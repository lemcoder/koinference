package io.github.lemcoder.koinference.benchmark.app.ui

import io.github.lemcoder.koinference.benchmark.app.client.BackendProcess

/**
 * One row of the backend list: what the app knows about an engine before anything is run.
 *
 * [unsupportedReason] non-null means this device cannot run the engine — llama.cpp's kernels are
 * compiled for instructions a CPU may not have. Such a row is shown and disabled rather than
 * hidden, because "why is this phone not offering llama.cpp" is a question the screen should
 * answer.
 */
data class BackendState(
    val process: BackendProcess,
    val engineId: String? = null,
    val models: List<String> = emptyList(),
    val selectedModel: String? = null,
    val unsupportedReason: String? = null,
    val probeFailure: String? = null,
) {
    val runnable: Boolean get() = unsupportedReason == null && probeFailure == null && models.isNotEmpty()

    /** Why this row cannot be run, in one line, or null when it can. */
    val blockedReason: String? get() = when {
        unsupportedReason != null -> unsupportedReason
        probeFailure != null -> probeFailure
        models.isEmpty() -> "no model on this device it can read"
        else -> null
    }
}
