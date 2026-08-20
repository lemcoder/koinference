package io.github.lemcoder.koinference.prompt

/**
 * One piece of a prompt.
 *
 * A prompt is a list of these rather than a string because the runtimes underneath are already
 * shaped that way — LiteRT-LM's conversation API takes a content array, and its C API has an
 * input-data type per modality. Flattening to a string would discard that at the seam and be
 * painful to undo once callers depend on it.
 *
 * File-backed and byte-backed variants are both here on purpose: they are not
 * interchangeable underneath. LiteRT-LM's conversation JSON references media by path, so a
 * file part is nearly free, while bytes have to go through the session input-data API or a
 * temporary file.
 */
sealed interface PromptPart {

    data class Text(val text: String) : PromptPart

    data class ImageFile(val path: String) : PromptPart

    data class AudioFile(val path: String) : PromptPart

    data class ImageBytes(val bytes: ByteArray) : PromptPart {
        override fun equals(other: Any?): Boolean =
            this === other || (other is ImageBytes && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "ImageBytes(${bytes.size} bytes)"
    }

    data class AudioBytes(val bytes: ByteArray) : PromptPart {
        override fun equals(other: Any?): Boolean =
            this === other || (other is AudioBytes && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "AudioBytes(${bytes.size} bytes)"
    }
}

/** Convenience for the common single-text-part prompt. */
fun promptOf(text: String): List<PromptPart> = listOf(PromptPart.Text(text))
