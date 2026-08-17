package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.GenerationConstraint
import io.github.lemcoder.koinference.GenerationParameters
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Generation against a real model, which is what proves the whole chain: cinterop over the
 * facade, the facade over the prebuilt runtime, and the runtime over the weights.
 *
 * Skipped unless KOI_TEST_LITERTLM points at a .litertlm file. The smallest published one is
 * SmolLM2-135M-Instruct at 136 MB, which is too large to keep in the repo or fetch in CI:
 *
 *     KOI_TEST_LITERTLM=/path/to/SmolLM2_135M_Instruct.litertlm \
 *         ./gradlew :backends:litertlm:macosArm64Test
 *
 * runBlocking rather than runTest: loading 136 MB and generating outruns runTest's 60 second
 * wall-clock timeout on a cold start, and the failure looks like a hang rather than a slow test.
 */
@OptIn(ExperimentalForeignApi::class)
class LiteRtLmGenerationTest {

    private val modelPath: String? = getenv("KOI_TEST_LITERTLM")?.toKString()

    @Test
    fun generatesAResponse() {
        val path = modelPath ?: return

        runBlocking {
            val loader = LiteRtLmModelLoader(systemPrompt = "You are terse.")
            try {
                val reply = loader.load(path).generateResponse("Say hello.")
                assertTrue(reply.isNotBlank(), "expected generated text, got: '$reply'")
            } finally {
                loader.unloadAll()
            }
        }
    }

    @Test
    fun honoursAJsonSchemaConstraint() {
        val path = modelPath ?: return

        runBlocking {
            val loader = LiteRtLmModelLoader()
            try {
                val schema =
                    """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""
                val reply = loader.load(path).generateResponse(
                    prompt = "Name a capital city.",
                    constraint = GenerationConstraint.JsonSchema(schema),
                )

                // llguidance constrains decoding token by token, so a well-formed object is a
                // property of the sampler rather than of the model happening to comply.
                assertTrue(reply.trimStart().startsWith("{"), "expected a JSON object, got: '$reply'")
                assertTrue(reply.contains("\"city\""), "expected the schema's field, got: '$reply'")
            } finally {
                loader.unloadAll()
            }
        }
    }

    @Test
    fun aFixedSeedRepeatsItself() {
        val path = modelPath ?: return

        runBlocking {
            // Greedy would prove nothing about the seed, so temperature stays high enough for
            // the sampler to have a choice to make.
            val loader = LiteRtLmModelLoader(
                parameters = GenerationParameters(seed = 42, temperature = 1.0, topK = 40),
            )
            try {
                val runtime = loader.load(path)
                val first = runtime.generateResponse("Name a colour.")
                // The same conversation would answer differently having heard the question
                // once already, so the history is dropped and the sampler starts over.
                runtime.resetConversation()
                val second = runtime.generateResponse("Name a colour.")

                assertEquals(first, second, "a fixed seed should replay the same sampling")
            } finally {
                loader.unloadAll()
            }
        }
    }
}
