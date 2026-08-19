package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.ModelConfig
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
            // No system prompt: whether one is accepted depends on the model's chat template,
            // and this test is about generation working at all. See [systemPromptEitherWorksOrSaysWhy].
            val loader = LiteRtLmModelLoader()
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

    /**
     * A fixed seed replays the same sampling — across engines, which is the only place this
     * runtime guarantees it.
     *
     * The obvious version of this test reuses one engine and resets the conversation between
     * the two calls. That fails, and not because seeding is broken: the sampler's random stream
     * is seeded when the engine is created and keeps advancing, so the second generation
     * continues where the first left off rather than replaying it. Reopening a conversation does
     * not rewind it. Comparing two engines is what "the seed decides the sampling" actually
     * means here.
     */
    @Test
    fun aFixedSeedRepeatsItselfAcrossEngines() {
        val path = modelPath ?: return

        runBlocking {
            // Greedy would prove nothing about the seed, so temperature stays high enough for
            // the sampler to have a choice to make.
            val replies = (1..2).map {
                val loader = LiteRtLmModelLoader(ModelConfig(parameters = GenerationParameters(seed = 42, temperature = 1.0, topK = 40)))
                try {
                    loader.load(path).generateResponse("Name a colour.")
                } finally {
                    loader.unloadAll()
                }
            }

            assertEquals(replies[0], replies[1], "a fixed seed should replay the same sampling")
        }
    }

    /**
     * Temperature 0 means argmax, the same as it does on llama.cpp.
     *
     * Worth its own test because it is not what the runtime does by default: its sampler keeps
     * sampling at temperature 0, and answers the same question differently on consecutive calls.
     * The backend maps temperature 0 onto top-k of 1 to make the two engines agree on what a
     * caller asking for no randomness gets.
     */
    @Test
    fun temperatureZeroGenerates() {
        val path = modelPath ?: return

        runBlocking {
            val loader = LiteRtLmModelLoader(ModelConfig(parameters = GenerationParameters(temperature = 0.0)))
            try {
                val reply = loader.load(path).generateResponse("Name a colour.")
                assertTrue(reply.isNotBlank(), "expected generated text, got: '$reply'")
            } finally {
                loader.unloadAll()
            }
        }
    }

    /**
     * Under argmax, conversations reopened on one engine answer identically.
     *
     * Not the same claim as "reset gives a clean slate", which is not true: the *first*
     * generation after an engine is loaded reliably differs from every later one — observed on a
     * single thread, so it is systematic rather than a reduction-order fluke — which means some
     * engine state outlives the conversation that produced it. What does hold is that every
     * reopened conversation agrees with the others, and that is the property a caller can use.
     *
     * The practical consequence is for benchmarking: on this backend the first iteration can
     * produce different text, not merely take longer, so warmup iterations have to be discarded
     * for correctness and not just for timing.
     */
    @Test
    fun reopenedConversationsAgreeWithEachOther() {
        val path = modelPath ?: return

        runBlocking {
            val loader = LiteRtLmModelLoader(ModelConfig(parameters = GenerationParameters(temperature = 0.0), threads = 1))
            try {
                val runtime = loader.load(path)
                // Discarded: the first generation on a fresh engine is the odd one out.
                runtime.generateResponse("Name a colour.")

                runtime.resetConversation()
                val second = runtime.generateResponse("Name a colour.")
                runtime.resetConversation()
                val third = runtime.generateResponse("Name a colour.")

                assertEquals(second, third, "reopened conversations should answer identically")
            } finally {
                loader.unloadAll()
            }
        }
    }

    /**
     * A system prompt either works or fails legibly.
     *
     * Whether the model accepts a system role is a property of its chat template, not of this
     * binding: SmolLM2-135M-Instruct takes one, LFM2.5-1.2B-Instruct refuses and the runtime
     * fails the send. Since the test suite runs against whichever model KOI_TEST_LITERTLM
     * points at, the assertion is on the failure being explicable rather than on which of the
     * two happens.
     */
    @Test
    fun systemPromptEitherWorksOrSaysWhy() {
        val path = modelPath ?: return

        runBlocking {
            val loader = LiteRtLmModelLoader(ModelConfig(systemPrompt = "You are terse."))
            try {
                val outcome = runCatching { loader.load(path).generateResponse("Say hello.") }
                outcome.onSuccess { reply ->
                    assertTrue(reply.isNotBlank(), "expected generated text, got: '$reply'")
                }.onFailure { failure ->
                    val message = failure.message.orEmpty()
                    assertTrue(
                        message.contains("system prompt", ignoreCase = true),
                        "a system prompt failure must name the system prompt, got: $message",
                    )
                }
            } finally {
                runCatching { loader.unloadAll() }
            }
        }
    }
}
