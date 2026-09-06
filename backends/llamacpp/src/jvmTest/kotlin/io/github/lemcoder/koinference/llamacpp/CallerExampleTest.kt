package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.Koinference
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.runtime.GeneratingConnection
import io.github.lemcoder.koinference.runtime.media.ResponsePart
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class CallerExampleTest {

    private val model: String? = System.getenv("KOI_TEST_GGUF")

    @Test
    fun `generate a response from a gguf on disk`() {
        val path = model ?: return
        runBlocking {
            val koi = Koinference(LlamaCpp, config = ModelConfig(maxOutputTokens = 24))
            val conn = koi.openConnection(koi.loadModel(path)) as GeneratingConnection

            val reply = conn.generateAll("What is the capital of France?")
                .filterIsInstance<ResponsePart.Text>().joinToString("") { it.text }

            assertTrue(reply.isNotBlank())
            println("buffered reply: $reply")
            conn.close(); koi.unloadAll()
        }
    }

    @Test
    fun `stream the same response`() {
        val path = model ?: return
        runBlocking {
            val koi = Koinference(LlamaCpp, config = ModelConfig(maxOutputTokens = 24))
            val conn = koi.openConnection(koi.loadModel(path)) as GeneratingConnection

            val text = mutableListOf<String>()
            conn.generate("What is the capital of France?") { part ->
                if (part is ResponsePart.Text) text += part.text
            }

            assertTrue(text.size > 1, "expected a stream, got ${text.size} part")
            println("streamed ${text.size} parts: ${text.joinToString("")}")
            conn.close(); koi.unloadAll()
        }
    }
}
