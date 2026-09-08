package io.github.lemcoder.koinference.cera

import io.github.lemcoder.koinference.Koinference
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.runtime.GeneratingConnection
import io.github.lemcoder.koinference.runtime.media.ResponsePart
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class CeraGenerationTest {

    private val model: String? = System.getenv("KOI_TEST_GGUF")

    private suspend fun Koinference.connect(path: String): CeraGeneratingConnection =
        openConnection(loadModel(path)) as CeraGeneratingConnection

    private suspend fun GeneratingConnection.streamedTexts(prompt: String): List<String> {
        val parts = mutableListOf<String>()
        generate(prompt) { if (it is ResponsePart.Text) parts += it.text }
        return parts
    }

    @Test
    fun `generates a reply`() {
        val path = model ?: return
        runBlocking {
            val koi = Koinference(Cera, config = ModelConfig(maxOutputTokens = 24))
            try {
                val reply = koi.connect(path).generateAll("What is the capital of France?").text()
                assertTrue(reply.isNotBlank(), "expected generated text, got: '$reply'")
                println("cera buffered reply: $reply")
            } finally { koi.unloadAll() }
        }
    }

    @Test
    fun `streams the reply in pieces`() {
        val path = model ?: return
        runBlocking {
            val koi = Koinference(Cera, config = ModelConfig(maxOutputTokens = 24))
            try {
                val parts = koi.connect(path).streamedTexts("Tell me a short story.")
                assertTrue(parts.size > 1, "expected a stream, got ${parts.size} part")
                println("cera streamed ${parts.size} parts: ${parts.joinToString("")}")
            } finally { koi.unloadAll() }
        }
    }

    @Test
    fun `streams one token per chunk`() {
        val path = model ?: return
        runBlocking {
            val koi = Koinference(Cera, config = ModelConfig(maxOutputTokens = 32))
            try {
                val conn = koi.connect(path)
                val parts = conn.streamedTexts("Count from one to twenty.")
                val tokens = conn.countTokens(parts.joinToString(""))
                assertTrue(parts.size >= tokens - 2,
                    "expected roughly one chunk per token, got ${parts.size} chunks for $tokens tokens")
            } finally { koi.unloadAll() }
        }
    }

    @Test
    fun `counts tokens with the model's own tokenizer`() {
        val path = model ?: return
        runBlocking {
            val koi = Koinference(Cera, config = ModelConfig(maxOutputTokens = 8))
            try {
                val count = koi.connect(path).countTokens("The capital of France is Paris.")
                assertTrue(count in 1..16, "implausible token count: $count")
            } finally { koi.unloadAll() }
        }
    }
}
