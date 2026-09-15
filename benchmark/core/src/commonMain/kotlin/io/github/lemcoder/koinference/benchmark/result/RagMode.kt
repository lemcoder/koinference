package io.github.lemcoder.koinference.benchmark.result

import kotlinx.serialization.Serializable

/**
 * Whether a workload retrieved context before generating.
 *
 * The axis exists to measure RAG's *speed* cost, which is almost entirely prefill: retrieved
 * context lengthens the prompt, so time to first token and KV-cache memory rise while decode
 * tok/s — bandwidth-bound on the model — barely moves. A run is one mode; comparing OFF and ON is
 * done by running twice and reading the two rows side by side, not by doubling a single run.
 */
@Serializable
enum class RagMode { OFF, ON }
