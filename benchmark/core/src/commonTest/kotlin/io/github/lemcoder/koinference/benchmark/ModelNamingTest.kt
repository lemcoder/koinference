package io.github.lemcoder.koinference.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelNamingTest {

    @Test
    fun `the same weights give the same id across containers`() {
        assertEquals("LFM2.5-1.2B-Instruct", modelIdOf("/m/LFM2.5-1.2B-Instruct-Q4_0.gguf"))
        assertEquals("LFM2.5-1.2B-Instruct", modelIdOf("/m/LFM2.5-1.2B-Instruct_int4.litertlm"))
    }

    @Test
    fun `quantization comes from the name and is lowercased`() {
        assertEquals("q4_0", quantizationOf("/m/LFM2.5-1.2B-Instruct-Q4_0.gguf"))
        assertEquals("int4", quantizationOf("/m/LFM2.5-1.2B-Instruct_int4.litertlm"))
    }

    @Test
    fun `a name with no quantization label says so rather than guessing`() {
        assertEquals("unknown", quantizationOf("/m/stories260K.gguf"))
        assertEquals("stories260K", modelIdOf("/m/stories260K.gguf"))
    }

    @Test
    fun `a quantization-looking word that is not a suffix is left alone`() {
        assertEquals("int4-tuned", modelIdOf("/m/int4-tuned.gguf"))
        assertEquals("unknown", quantizationOf("/m/int4-tuned.gguf"))
    }

    @Test
    fun `every label the corpus of names uses round-trips`() {
        listOf("q4_0", "q4_k_m", "q5_k_m", "q6_k", "q8_0", "int4", "int8", "f16", "bf16", "f32")
            .forEach { label ->
                val path = "/m/Model-$label.gguf"
                assertEquals("Model", modelIdOf(path), "modelId for $label")
                assertEquals(label, quantizationOf(path), "quantization for $label")
            }
    }
}
