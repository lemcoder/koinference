package io.github.lemcoder.koinference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PromptTest {

    @Test
    fun promptOfIsASingleTextPart() {
        assertEquals(listOf(PromptPart.Text("hello")), promptOf("hello"))
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
