package io.github.lemcoder.koinference.benchmark.result

import kotlinx.serialization.Serializable

@Serializable
data class WorkloadInfo(
    val promptId: String,
    val promptSha256: String? = null,
    /** Characters, not tokens: the harness has no tokenizer of its own and will not guess. */
    val promptChars: Int,
    val maxNewTokens: Int,
    /** OFF unless the workload retrieved context; defaulted so pre-RAG files parse as baseline. */
    val ragMode: RagMode = RagMode.OFF,
    /** Passages retrieved when [ragMode] is ON. */
    val ragK: Int? = null,
    /** Characters of retrieved context prepended to the prompt, so its prefill cost is visible. */
    val ragContextChars: Int? = null,
)
