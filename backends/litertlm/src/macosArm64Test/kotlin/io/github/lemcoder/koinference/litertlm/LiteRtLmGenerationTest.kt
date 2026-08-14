package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.GenerationConstraint
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import platform.posix.getenv
import kotlin.test.Test
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
 */
@OptIn(ExperimentalForeignApi::class)
class LiteRtLmGenerationTest {

    private val modelPath: String? = getenv("KOI_TEST_LITERTLM")?.toKString()

    @Test
    fun generatesAResponse() = runTest {
        val path = modelPath ?: return@runTest

        val loader = LiteRtLmModelLoader(systemPrompt = "You are terse.")
        val runtime = loader.load(path)

        val reply = runtime.generateResponse("Say hello.")
        assertTrue(reply.isNotBlank(), "expected generated text, got: '$reply'")

        loader.unload(path)
    }

    @Test
    fun honoursAJsonSchemaConstraint() = runTest {
        val path = modelPath ?: return@runTest

        val loader = LiteRtLmModelLoader()
        val runtime = loader.load(path)

        val schema = """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""
        val reply = runtime.generateResponse(
            prompt = "Name a capital city.",
            constraint = GenerationConstraint.JsonSchema(schema),
        )

        // llguidance constrains decoding token by token, so a well-formed object is a
        // property of the sampler rather than of the model happening to comply.
        assertTrue(reply.trimStart().startsWith("{"), "expected a JSON object, got: '$reply'")
        assertTrue(reply.contains("\"city\""), "expected the schema's field, got: '$reply'")

        loader.unload(path)
    }
}
