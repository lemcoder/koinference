package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.Koinference
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.runtime.ResponsePart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What a caller with a GGUF on disk actually writes, compiled and run.
 *
 * A test rather than a README snippet so it cannot drift from the API: if the caller story gets
 * worse, this stops compiling.
 *
 * Note the filter. A reply is a list of [ResponsePart] because some models interleave text with
 * audio, and the library offers no shortcut that hides it — a caller narrowing to text should be
 * able to see that it is dropping whatever else the model produced. For a GGUF there is nothing else
 * to drop, and the filter says so out loud.
 */
class CallerExampleTest {

    private val model: String? = System.getenv("KOI_TEST_GGUF")

    @Test
    fun `generate a response from a gguf on disk`() {
        val path = model ?: return

        runBlocking {
            val koi = Koinference(LlamaCpp, config = ModelConfig(maxOutputTokens = 24))
            val runtime = koi.load(path)

            val reply = runtime.generateResponse("What is the capital of France?")
                .filterIsInstance<ResponsePart.Text>()
                .joinToString("") { it.text }

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
            val runtime = koi.load(path)

            val parts = runtime.streamResponse("What is the capital of France?").toList()
            val text = parts.filterIsInstance<ResponsePart.Text>().map { it.text }

            assertTrue(text.size > 1, "expected a stream, got ${text.size} part")
            println("streamed ${text.size} parts: ${text.joinToString("")}")
            koi.unloadAll()
        }
    }
}
