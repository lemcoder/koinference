package io.github.lemcoder.koinference.llamacpp.internal

/** Chooses a placement. One call, so the platform legs stay as small as the decision they make. */
internal fun interface CpuPlacementSource {
    fun choose(): CpuPlacement
}
