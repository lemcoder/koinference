package io.github.lemcoder.koinference.llamacpp

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Argument checking only. Everything past `require` reaches llama.cpp, and this source set also
 * compiles into the Android unit-test variant, which has no native library to reach — those
 * tests live in [LlamaCppGenerationTest] (host targets) and `LlamaCppDeviceTest` (Android).
 */
class LlamaCppModelLoaderTest {

    @Test
    fun `load rejects non gguf models`() = runTest {
        val failure = assertFailsWith<IllegalArgumentException> {
            LlamaCppModelLoader().load("test-model.bin")
        }
        assertTrue(failure.message!!.contains(".gguf"))
    }
}
