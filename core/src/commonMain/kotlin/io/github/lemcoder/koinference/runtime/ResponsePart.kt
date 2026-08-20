package io.github.lemcoder.koinference.runtime

/**
 * One piece of a reply.
 *
 * The mirror of `PromptPart`, and for the same reason: a reply is a sequence of parts rather than a
 * string because the models underneath are already shaped that way. Some produce text and audio
 * interleaved in one response, so an interface returning `String` or a single image cannot express
 * what they do — which is what an earlier split of the runtimes by output type got wrong.
 *
 * A backend that only produces text emits nothing but [Text]. Nothing forces a caller to think about
 * the rest: `TextRuntime.generateResponse` still returns a `String`, and `streamText` filters the
 * stream down to text.
 */
sealed interface ResponsePart {

    data class Text(val text: String) : ResponsePart

    /**
     * Not a data class: a generated `equals` on a [ByteArray] compares references, which makes two
     * identical clips unequal and is a trap in tests. Same reason `PromptPart.ImageBytes` is not.
     */
    class Audio(
        val bytes: ByteArray,
        val format: AudioFormat,
        val sampleRateHz: Int,
    ) : ResponsePart {
        override fun equals(other: Any?): Boolean =
            this === other || (
                other is Audio &&
                    format == other.format &&
                    sampleRateHz == other.sampleRateHz &&
                    bytes.contentEquals(other.bytes)
                )

        override fun hashCode(): Int =
            31 * (31 * bytes.contentHashCode() + format.hashCode()) + sampleRateHz

        override fun toString(): String =
            "Audio($format ${sampleRateHz}Hz, ${bytes.size} bytes)"
    }

    /** See [Audio] for why this is not a data class. */
    class Image(
        val bytes: ByteArray,
        val format: ImageFormat,
        val width: Int,
        val height: Int,
    ) : ResponsePart {
        override fun equals(other: Any?): Boolean =
            this === other || (
                other is Image &&
                    format == other.format &&
                    width == other.width &&
                    height == other.height &&
                    bytes.contentEquals(other.bytes)
                )

        override fun hashCode(): Int =
            31 * (31 * (31 * bytes.contentHashCode() + format.hashCode()) + width) + height

        override fun toString(): String = "Image($format ${width}x$height, ${bytes.size} bytes)"
    }
}
