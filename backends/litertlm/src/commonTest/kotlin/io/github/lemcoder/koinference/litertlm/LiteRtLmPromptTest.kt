package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.PromptPart
import io.github.lemcoder.koinference.textOnly
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The rejection path is checked here rather than through [LiteRtLmRuntime], because reaching
 * `generateResponse` needs a loaded engine and so a real model. The runtime calls exactly this,
 * with exactly this backend name.
 */
class LiteRtLmPromptTest {

    @Test
    fun imagePartsAreRejectedUntilVisionIsWired() {
        val failure = assertFailsWith<UnsupportedOperationException> {
            listOf(
                PromptPart.Text("What is in this picture? "),
                PromptPart.ImageBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)),
            ).textOnly("LiteRT-LM")
        }
        assertTrue(failure.message!!.contains("LiteRT-LM"), failure.message!!)
        assertTrue(failure.message!!.contains("ImageBytes"), failure.message!!)
    }
}
