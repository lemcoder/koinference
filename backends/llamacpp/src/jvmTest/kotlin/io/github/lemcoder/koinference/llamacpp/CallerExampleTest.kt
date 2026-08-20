package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.backend.BackendRegistry
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.runtime.StreamingTextRuntime
import io.github.lemcoder.koinference.runtime.TextRuntime
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What a caller with a GGUF on disk actually writes, compiled and run.
 *
 * Here as a test rather than as a README snippet so it cannot drift from the API: if the caller
 * story gets worse, this stops compiling.
 */
class CallerExampleTest {

    private val model: String? = System.getenv("KOI_TEST_GGUF")

    @Test
    fun `generate a response from a gguf on disk`() {
        val path = model ?: return

        runBlocking {
            val backends = BackendRegistry(LlamaCpp)
            val loader = backends.requireForModel(path).loader(ModelConfig(maxOutputTokens = 24))
            val runtime = loader.load(path) as TextRuntime

            val reply = runtime.generateResponse("What is the capital of France?")

            assertTrue(reply.isNotBlank())
            println("blocking reply: $reply")
            loader.unloadAll()
        }
    }

    @Test
    fun `stream the same response`() {
        val path = model ?: return

        runBlocking {
            val backends = BackendRegistry(LlamaCpp)
            val loader = backends.requireForModel(path).loader(ModelConfig(maxOutputTokens = 24))
            val runtime = loader.load(path) as StreamingTextRuntime

            val chunks = runtime.streamResponse("What is the capital of France?").toList()

            assertTrue(chunks.size > 1, "expected a stream, got ${chunks.size} chunk")
            println("streamed ${chunks.size} chunks: ${chunks.joinToString("")}")
            loader.unloadAll()
        }
    }
}
