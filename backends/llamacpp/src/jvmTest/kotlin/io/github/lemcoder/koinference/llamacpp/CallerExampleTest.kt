package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.Koinference
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.runtime.text.StreamingTextRuntime
import io.github.lemcoder.koinference.runtime.text.TextModelRuntime
import io.github.lemcoder.koinference.runtime.text.TextRuntime
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * What a caller with a GGUF on disk actually writes, compiled and run.
 *
 * Here as a test rather than as a README snippet so it cannot drift from the API: if the caller
 * story gets worse, this stops compiling. Two lines and no cast — `load` returns a
 * `TextModelRuntime`, so a caller who wants a reply does not first have to prove what kind of
 * runtime it got.
 */
class CallerExampleTest {

    private val model: String? = System.getenv("KOI_TEST_GGUF")

    @Test
    fun `generate a response from a gguf on disk`() {
        val path = model ?: return

        runBlocking {
            val koi = Koinference(LlamaCpp, config = ModelConfig(maxOutputTokens = 24))
            val runtime = koi.loadText(path)

            val reply = runtime.generateResponse("What is the capital of France?")

            assertTrue(reply.isNotBlank())
            println("blocking reply: $reply")
            koi.unloadAll()
        }
    }

    @Test
    fun `stream the same response`() {
        val path = model ?: return

        runBlocking {
            val koi = Koinference(LlamaCpp, config = ModelConfig(maxOutputTokens = 24))
            val runtime = koi.loadText(path)

            val chunks = runtime.streamResponse("What is the capital of France?").toList()

            assertTrue(chunks.size > 1, "expected a stream, got ${chunks.size} chunk")
            println("streamed ${chunks.size} chunks: ${chunks.joinToString("")}")
            koi.unloadAll()
        }
    }
}
