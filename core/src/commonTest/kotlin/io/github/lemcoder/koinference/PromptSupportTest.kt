package io.github.lemcoder.koinference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PromptSupportTest {

    @Test
    fun flattensASingleTextPart() {
        assertEquals("hello", promptOf("hello").textOnly("test"))
    }

    @Test
    fun concatenatesTextPartsInOrder() {
        val prompt = listOf(PromptPart.Text("one "), PromptPart.Text("two"))
        assertEquals("one two", prompt.textOnly("test"))
    }

    @Test
    fun rejectsAnImagePartRatherThanDroppingIt() {
        val prompt = listOf(PromptPart.Text("describe: "), PromptPart.ImageFile("/a.png"))

        val failure = assertFailsWith<UnsupportedOperationException> {
            prompt.textOnly("llama.cpp")
        }
        // The message has to name both the backend and the part, or a caller cannot tell
        // whether to change the prompt or the backend.
        assertTrue(failure.message!!.contains("llama.cpp"), failure.message!!)
        assertTrue(failure.message!!.contains("ImageFile"), failure.message!!)
    }

    @Test
    fun byteBackedPartsCompareByContent() {
        // A data class would have compared ByteArray by reference, making these unequal.
        assertEquals(
            PromptPart.ImageBytes(byteArrayOf(1, 2, 3)),
            PromptPart.ImageBytes(byteArrayOf(1, 2, 3)),
        )
        assertEquals(
            PromptPart.ImageBytes(byteArrayOf(1, 2, 3)).hashCode(),
            PromptPart.ImageBytes(byteArrayOf(1, 2, 3)).hashCode(),
        )
        assertNotEquals(
            PromptPart.ImageBytes(byteArrayOf(1, 2, 3)),
            PromptPart.ImageBytes(byteArrayOf(3, 2, 1)),
        )
        // Image and audio bytes are different parts even with identical payloads.
        assertNotEquals<PromptPart>(
            PromptPart.ImageBytes(byteArrayOf(1)),
            PromptPart.AudioBytes(byteArrayOf(1)),
        )
    }
}
