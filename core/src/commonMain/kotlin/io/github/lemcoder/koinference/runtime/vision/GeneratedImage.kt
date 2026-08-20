package io.github.lemcoder.koinference.runtime.vision

/**
 * One image a model produced.
 *
 * Bytes rather than a platform bitmap: `:core` is common code and has no Bitmap, UIImage or
 * BufferedImage to hand back, and a caller that wants one can decode these. [format] is here so it
 * does not have to be sniffed.
 *
 * Not a data class, for the reason `PromptPart.ImageBytes` is not: a generated `equals` on a
 * [ByteArray] compares references, which makes two identical images unequal and is a trap in tests.
 */
class GeneratedImage(
    val bytes: ByteArray,
    val format: ImageFormat,
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is GeneratedImage &&
                format == other.format &&
                width == other.width &&
                height == other.height &&
                bytes.contentEquals(other.bytes)
            )

    override fun hashCode(): Int =
        31 * (31 * (31 * bytes.contentHashCode() + format.hashCode()) + width) + height

    override fun toString(): String = "GeneratedImage($format ${width}x$height, ${bytes.size} bytes)"
}
