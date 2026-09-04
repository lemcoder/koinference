package io.github.lemcoder.koinference.onnx.internal

/**
 * One padded batch, in the shape the graph takes.
 *
 * Padded to the longest row rather than to the model's limit: a batch of short texts should not pay
 * for 512 positions it does not use.
 */
internal data class TokenBatch(
    val inputIds: Array<LongArray>,
    val attentionMask: Array<LongArray>,
    val tokenTypeIds: Array<LongArray>,
) {
    val size: Int get() = inputIds.size
    val length: Int get() = if (inputIds.isEmpty()) 0 else inputIds[0].size

    override fun equals(other: Any?): Boolean = other is TokenBatch &&
        inputIds.contentDeepEquals(other.inputIds) &&
        attentionMask.contentDeepEquals(other.attentionMask) &&
        tokenTypeIds.contentDeepEquals(other.tokenTypeIds)

    override fun hashCode(): Int = inputIds.contentDeepHashCode()
}
