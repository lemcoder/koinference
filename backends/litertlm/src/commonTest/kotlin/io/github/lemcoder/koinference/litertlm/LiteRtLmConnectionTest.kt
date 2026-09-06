package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.runtime.generation.GenerationConstraint
import io.github.lemcoder.koinference.runtime.generation.GenerationParameters
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.prompt.promptOf
import io.github.lemcoder.koinference.runtime.KoinferenceException
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import io.github.lemcoder.koinference.litertlm.internal.DEFAULT_TEMPERATURE
import io.github.lemcoder.koinference.litertlm.internal.DEFAULT_TOP_K
import io.github.lemcoder.koinference.litertlm.internal.DEFAULT_TOP_P
import io.github.lemcoder.koinference.litertlm.internal.FakeLiteRtLmBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MODEL = "/models/smol.litertlm"

class LiteRtLmConnectionTest {

    private val bridge = FakeLiteRtLmBridge()

    private suspend fun connection(
        parameters: GenerationParameters = GenerationParameters(),
        settings: RuntimeSettings = RuntimeSettings(),
        bridge: FakeLiteRtLmBridge = this.bridge,
    ): LiteRtLmGeneratingConnection = LiteRtLmModelLoader(
        bridge = bridge,
        config = ModelConfig(systemPrompt = "You are terse.", settings = settings, parameters = parameters),
    ).load(MODEL).open() as LiteRtLmGeneratingConnection

    private suspend fun LiteRtLmGeneratingConnection.reply(prompt: String) = generateAll(prompt).text()

    @Test
    fun reusesOneConversationAcrossTurns() = runTest {
        val conn = connection()
        conn.reply("one"); conn.reply("two")
        assertEquals(1, bridge.engine.conversations.size)
        assertEquals(listOf("one", "two"), bridge.engine.conversation.turns.map { it.prompt })
    }

    @Test
    fun opensTheConversationWithTheParametersItWasGiven() = runTest {
        connection(GenerationParameters(topK = 7, topP = 0.5, temperature = 0.1, seed = 3)).reply("hello")
        val options = bridge.engine.conversation.options
        assertEquals(7, options.topK)
        assertEquals(0.5f, options.topP)
        assertEquals(0.1f, options.temperature)
        assertEquals(3, options.seed)
        assertEquals("You are terse.", options.systemPrompt)
    }

    @Test
    fun unsetKnobsFallBackToTheSharedDefaults() = runTest {
        connection().reply("hello")
        val options = bridge.engine.conversation.options
        assertEquals(DEFAULT_TOP_K, options.topK)
        assertEquals(DEFAULT_TOP_P, options.topP)
        assertEquals(DEFAULT_TEMPERATURE, options.temperature)
        assertNull(options.seed)
    }

    @Test
    fun minPIsNotPassedOffAsTopP() = runTest {
        connection(GenerationParameters(minP = 0.05)).reply("hello")
        assertEquals(DEFAULT_TOP_P, bridge.engine.conversation.options.topP)
    }

    @Test
    fun passesTheSchemaThrough() = runTest {
        val schema = """{"type":"object"}"""
        connection().generateAll(promptOf("hello"), GenerationConstraint.JsonSchema(schema))
        assertEquals(schema, bridge.engine.conversation.turns.single().jsonSchema)
    }

    @Test
    fun changingParametersReopensTheConversationButKeepsTheEngine() = runTest {
        val conn = connection()
        conn.reply("one")
        val first = bridge.engine.conversation
        conn.updateGenerationParameters(GenerationParameters(topK = 1))
        conn.reply("two")
        assertTrue(first.closed, "the old conversation was leaked")
        assertEquals(2, bridge.engine.conversations.size)
        assertEquals(1, bridge.engine.conversation.options.topK)
        assertEquals(1, bridge.engines.size, "the engine should not have been reloaded")
        assertEquals(GenerationParameters(topK = 1), conn.generationParameters)
    }

    @Test
    fun settingTheSameParametersKeepsTheConversation() = runTest {
        val conn = connection(GenerationParameters(topK = 1))
        conn.reply("one")
        conn.updateGenerationParameters(GenerationParameters(topK = 1))
        conn.reply("two")
        assertEquals(1, bridge.engine.conversations.size, "history was dropped for nothing")
    }

    @Test
    fun resettingDropsTheHistoryAndKeepsTheParameters() = runTest {
        val conn = connection(GenerationParameters(topK = 3))
        conn.reply("one")
        conn.resetConversation()
        conn.reply("two")
        assertEquals(2, bridge.engine.conversations.size)
        assertTrue(bridge.engine.conversations.first().closed)
        assertEquals(listOf("two"), bridge.engine.conversation.turns.map { it.prompt })
        assertEquals(3, bridge.engine.conversation.options.topK)
        assertEquals(1, bridge.engines.size, "resetting should not touch the engine")
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
        assertTrue(bridge.engine.conversations.isEmpty())
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
    fun closeWaitsForAnInFlightGeneration() = runTest {
        val conn = connection()
        conn.reply("warm up")

        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var conversationWasOpen = false
        bridge.engine.conversation.whileGenerating = {
            started.complete(Unit)
            runBlocking { release.await() }
            conversationWasOpen = !bridge.engine.conversation.closed
        }

        val generating = launch(Dispatchers.Default) { conn.reply("slow") }
        started.await()
        val closing = launch(Dispatchers.Default) { conn.close() }
        release.complete(Unit)
        generating.join(); closing.join()

        assertTrue(conversationWasOpen, "the conversation was freed under a running generation")
        assertTrue(bridge.engine.conversation.closed)
    }
}
