package io.github.lemcoder.koinference.litertlm

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LiteRtLmModelLoaderTest {

    @Test
    fun rejectsAGgufPath() = runTest {
        val failure = assertFailsWith<IllegalArgumentException> {
            LiteRtLmModelLoader().load("/models/tinyllama.gguf")
        }
        assertTrue(failure.message!!.contains(".litertlm"))
    }

    @Test
    fun rejectsARawTfliteModel() = runTest {
        // LiteRT-LM refuses these itself; failing here keeps the error legible instead of
        // surfacing as a null engine handle from the facade.
        assertFailsWith<IllegalArgumentException> {
            LiteRtLmModelLoader().load("/models/model.tflite")
        }
    }
}
