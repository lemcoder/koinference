package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.prompt.promptOf
import io.github.lemcoder.koinference.runtime.GeneratingConnection
import io.github.lemcoder.koinference.runtime.KoinferenceException
import io.github.lemcoder.koinference.runtime.generation.Accelerator
import io.github.lemcoder.koinference.runtime.generation.GenerationConstraint
import io.github.lemcoder.koinference.runtime.generation.GenerationParameters
import io.github.lemcoder.koinference.runtime.media.ResponsePart
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.llamacpp.internal.DEFAULT_MIN_P
import io.github.lemcoder.koinference.llamacpp.internal.DEFAULT_TEMPERATURE
import io.github.lemcoder.koinference.llamacpp.internal.DEFAULT_TOP_K
import io.github.lemcoder.koinference.llamacpp.internal.CpuPlacementPolicy
import io.github.lemcoder.koinference.llamacpp.internal.CpuPlacementSource
import io.github.lemcoder.koinference.llamacpp.internal.FakeLlamaCppBridge
import io.github.lemcoder.koinference.llamacpp.internal.MutableMachine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val MODEL = "/models/stories.gguf"

class LlamaCppConnectionTest {

    private val bridge = FakeLlamaCppBridge()

    private fun loader(
        parameters: GenerationParameters = GenerationParameters(),
        settings: RuntimeSettings = RuntimeSettings(),
        bridge: FakeLlamaCppBridge = this.bridge,
        placementPolicy: CpuPlacementSource = CpuPlacementPolicy(MutableMachine()),
    ) = LlamaCppModelLoader(
        bridge = bridge,
        placementPolicy = placementPolicy,
        config = ModelConfig(
            systemPrompt = "You are terse.",
            settings = settings,
            parameters = parameters,
            contextTokens = 512,
            maxOutputTokens = 32,
            threads = 2,
        ),
    )

    private suspend fun connection(
        parameters: GenerationParameters = GenerationParameters(),
        settings: RuntimeSettings = RuntimeSettings(),
        bridge: FakeLlamaCppBridge = this.bridge,
        placementPolicy: CpuPlacementSource = CpuPlacementPolicy(MutableMachine()),
    ): GeneratingConnection =
        loader(parameters, settings, bridge, placementPolicy).load(MODEL).open() as GeneratingConnection

    private suspend fun GeneratingConnection.reply(prompt: String): String = generateAll(prompt).text()

    private suspend fun GeneratingConnection.streamed(prompt: String): List<String> {
        val parts = mutableListOf<ResponsePart>()
        generate(promptOf(prompt)) { parts += it }
        return parts.textParts()
    }

    @Test
    fun reusesOneSessionAcrossTurns() = runTest {
        val conn = connection()
        conn.reply("one"); conn.reply("two")
        assertEquals(1, bridge.model.sessions.size)
        assertEquals(listOf("one", "two"), bridge.model.session.turns.map { it.prompt })
    }

    @Test
    fun opensTheSessionWithTheParametersItWasGiven() = runTest {
        connection(GenerationParameters(topK = 7, minP = 0.2, temperature = 0.1)).reply("hello")
        val options = bridge.model.session.options
        assertEquals(7, options.topK)
        assertEquals(0.2f, options.minP)
        assertEquals(0.1f, options.temperature)
        assertEquals(512, options.nCtx)
        assertEquals(2, options.nThreads)
        assertEquals(32, options.nPredict)
    }

    @Test
    fun unsetKnobsFallBackToTheFacadeDefaults() = runTest {
        connection().reply("hello")
        val options = bridge.model.session.options
        assertEquals(DEFAULT_TOP_K, options.topK)
        assertEquals(DEFAULT_MIN_P, options.minP)
        assertEquals(DEFAULT_TEMPERATURE, options.temperature)
    }

    @Test
    fun topPIsNotPassedOffAsMinP() = runTest {
        connection(GenerationParameters(topP = 0.5)).reply("hello")
        assertEquals(DEFAULT_MIN_P, bridge.model.session.options.minP)
    }

    @Test
    fun sendsTheSystemPromptWithEveryTurn() = runTest {
        val conn = connection()
        conn.reply("one"); conn.reply("two")
        assertTrue(bridge.model.session.turns.all { it.systemPrompt == "You are terse." })
    }

    @Test
    fun convertsASchemaToAGrammarAndPassesItDown() = runTest {
        val schema = """{"type":"object"}"""
        connection().generateAll(promptOf("hello"), GenerationConstraint.JsonSchema(schema))
        assertEquals("grammar for $schema", bridge.model.session.turns.single().grammar)
    }

    @Test
    fun anUnconvertibleSchemaIsRejectedRatherThanGeneratingUnconstrained() = runTest {
        bridge.unconvertibleSchemas = setOf("nonsense")
        val conn = connection()
        assertFailsWith<IllegalArgumentException> {
            conn.generateAll(promptOf("hello"), GenerationConstraint.JsonSchema("nonsense"))
        }
        assertTrue(bridge.model.sessions.all { it.turns.isEmpty() })
    }

    @Test
    fun changingParametersRebuildsTheSessionButKeepsTheWeights() = runTest {
        val conn = connection()
        conn.reply("one")
        val first = bridge.model.session

        conn.updateGenerationParameters(GenerationParameters(topK = 1))
        conn.reply("two")

        assertTrue(first.closed, "the old session was leaked")
        assertEquals(2, bridge.model.sessions.size)
        assertEquals(1, bridge.model.session.options.topK)
        assertEquals(1, bridge.models.size, "the weights should not have been reloaded")
        assertEquals(GenerationParameters(topK = 1), conn.generationParameters)
    }

    @Test
    fun settingTheSameParametersKeepsTheSession() = runTest {
        val conn = connection(GenerationParameters(topK = 1))
        conn.reply("one")
        conn.updateGenerationParameters(GenerationParameters(topK = 1))
        conn.reply("two")
        assertEquals(1, bridge.model.sessions.size, "the KV cache was dropped for nothing")
    }

    @Test
    fun loadsOnTheBackendTheLoaderWasConfiguredWith() = runTest {
        connection(settings = RuntimeSettings(Accelerator.GPU))
        assertEquals(Accelerator.GPU, bridge.models.single().options.accelerator)
    }

    @Test
    fun generatingAfterCloseFails() = runTest {
        val conn = connection()
        conn.reply("one")
        conn.close()
        val failure = assertFailsWith<KoinferenceException.ConnectionClosed> { conn.reply("two") }
        assertTrue(failure.message!!.contains(MODEL), failure.message!!)
    }

    @Test
    fun rejectsPartsItCannotSend() = runTest {
        val conn = connection()
        assertFailsWith<ClassCastException> {
            conn.generateAll(
                listOf(
                    PromptPart.Text("What is in this picture? "),
                    PromptPart.ImageBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)),
                ),
            )
        }
        assertTrue(bridge.model.sessions.isEmpty())
    }

    @Test
    fun streamsChunksAndConcatenatesToTheBufferedReply() = runTest {
        val conn = connection()
        val streamed = conn.streamed("hello")
        val buffered = conn.reply("hello")
        assertTrue(streamed.size > 1, "a stream that arrives in one piece is not a stream")
        assertEquals(buffered, streamed.joinToString(""))
    }

    @Test
    fun aTurnHoldsTheConnectionSoASecondCannotInterleave() = runTest {
        val conn = connection()
        conn.streamed("first")
        conn.reply("second")
        assertEquals(listOf("first", "second"), bridge.model.session.turns.map { it.prompt })
    }

    @Test
    fun countsTokensWithTheModelsOwnTokenizer() = runTest {
        val conn = connection()
        assertEquals(3, conn.let { (it as io.github.lemcoder.koinference.runtime.text.TokenCounting).countTokens("one two three") })
        assertEquals(1, bridge.model.sessions.size)
    }

    @Test
    fun closeWaitsForAnInFlightGeneration() = runTest {
        val conn = connection()
        conn.reply("warm up")

        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var sessionWasOpen = false
        bridge.model.session.whileGenerating = {
            started.complete(Unit)
            runBlocking { release.await() }
            sessionWasOpen = !bridge.model.session.closed
        }

        val generating = launch(Dispatchers.Default) { conn.reply("slow") }
        started.await()
        val closing = launch(Dispatchers.Default) { conn.close() }
        release.complete(Unit)
        generating.join(); closing.join()

        assertTrue(sessionWasOpen, "the session was freed under a running generation")
        assertTrue(bridge.model.session.closed)
    }

    @Test
    fun placesTheThreadsBeforeDecodingAndOnlyOnce() = runTest {
        val conn = connection()
        conn.reply("one"); conn.reply("two")
        assertEquals(listOf(listOf(4, 5, 6, 7)), bridge.model.session.maskHistory)
    }

    @Test
    fun repinsWhenTheUsableCpusChange() = runTest {
        val machine = MutableMachine()
        val conn = connection(placementPolicy = CpuPlacementPolicy(machine))
        conn.reply("foreground")
        machine.permitted = "0-3"
        conn.reply("background")
        assertEquals(listOf(listOf(4, 5, 6, 7), emptyList()), bridge.model.session.maskHistory)
    }

    @Test
    fun aNewSessionIsPlacedAgain() = runTest {
        val conn = connection()
        conn.reply("one")
        conn.updateGenerationParameters(GenerationParameters(topK = 1))
        conn.reply("two")
        assertEquals(listOf(listOf(4, 5, 6, 7)), bridge.model.session.maskHistory)
    }
}
