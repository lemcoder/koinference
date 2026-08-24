package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.runtime.GenerationConstraint
import io.github.lemcoder.koinference.runtime.GenerationParameters
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.runtime.Accelerator
import io.github.lemcoder.koinference.prompt.PromptPart
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

class LiteRtLmRuntimeTest {

    private val bridge = FakeLiteRtLmBridge()

    private suspend fun runtime(
        parameters: GenerationParameters = GenerationParameters(),
        settings: RuntimeSettings = RuntimeSettings(),
        bridge: FakeLiteRtLmBridge = this.bridge,
    ): LiteRtLmTextRuntime = LiteRtLmModelLoader(
        bridge = bridge,
        config = ModelConfig(
            systemPrompt = "You are terse.",
            settings = settings,
            parameters = parameters,
        ),
    ).load(MODEL)

    @Test
    fun reusesOneConversationAcrossTurns() = runTest {
        val runtime = runtime()

        runtime.generateResponse("one").text()
        runtime.generateResponse("two").text()

        assertEquals(1, bridge.engine.conversations.size)
        assertEquals(
            listOf("one", "two"),
            bridge.engine.conversation.turns.map { it.prompt },
        )
    }

    @Test
    fun opensTheConversationWithTheParametersItWasGiven() = runTest {
        runtime(GenerationParameters(topK = 7, topP = 0.5, temperature = 0.1, seed = 3))
            .generateResponse("hello").text()

        val options = bridge.engine.conversation.options
        assertEquals(7, options.topK)
        assertEquals(0.5f, options.topP)
        assertEquals(0.1f, options.temperature)
        assertEquals(3, options.seed)
        assertEquals("You are terse.", options.systemPrompt)
    }

    @Test
    fun unsetKnobsFallBackToTheSharedDefaults() = runTest {
        runtime().generateResponse("hello").text()

        val options = bridge.engine.conversation.options
        assertEquals(DEFAULT_TOP_K, options.topK)
        assertEquals(DEFAULT_TOP_P, options.topP)
        assertEquals(DEFAULT_TEMPERATURE, options.temperature)
        // Unseeded, so the two legs behave the same when the caller did not ask.
        assertNull(options.seed)
    }

    @Test
    fun minPIsNotPassedOffAsTopP() = runTest {
        runtime(GenerationParameters(minP = 0.05)).generateResponse("hello").text()

        assertEquals(DEFAULT_TOP_P, bridge.engine.conversation.options.topP)
    }

    @Test
    fun passesTheSchemaThrough() = runTest {
        val schema = """{"type":"object"}"""

        runtime().generateResponse("hello", GenerationConstraint.JsonSchema(schema)).text()

        assertEquals(schema, bridge.engine.conversation.turns.single().jsonSchema)
    }

    @Test
    fun changingParametersReopensTheConversationButKeepsTheEngine() = runTest {
        val runtime = runtime()
        runtime.generateResponse("one").text()
        val first = bridge.engine.conversation

        runtime.updateGenerationParameters(GenerationParameters(topK = 1))
        runtime.generateResponse("two").text()

        assertTrue(first.closed, "the old conversation was leaked")
        assertEquals(2, bridge.engine.conversations.size)
        assertEquals(1, bridge.engine.conversation.options.topK)
        assertEquals(1, bridge.engines.size, "the engine should not have been reloaded")
        assertEquals(GenerationParameters(topK = 1), runtime.generationParameters)
    }

    @Test
    fun settingTheSameParametersKeepsTheConversation() = runTest {
        val runtime = runtime(GenerationParameters(topK = 1))
        runtime.generateResponse("one").text()

        runtime.updateGenerationParameters(GenerationParameters(topK = 1))
        runtime.generateResponse("two").text()

        assertEquals(1, bridge.engine.conversations.size, "history was dropped for nothing")
    }

    @Test
    fun resettingDropsTheHistoryAndKeepsTheParameters() = runTest {
        val runtime = runtime(GenerationParameters(topK = 3))
        runtime.generateResponse("one").text()

        runtime.resetConversation()
        runtime.generateResponse("two").text()

        assertEquals(2, bridge.engine.conversations.size)
        assertTrue(bridge.engine.conversations.first().closed)
        assertEquals(listOf("two"), bridge.engine.conversation.turns.map { it.prompt })
        assertEquals(3, bridge.engine.conversation.options.topK)
        assertEquals(1, bridge.engines.size, "resetting should not touch the engine")
    }

    @Test
    fun changingTheBackendReloadsTheModelOnIt() = runTest {
        val runtime = runtime()
        runtime.generateResponse("one").text()
        val cpuEngine = bridge.engine

        runtime.updateRuntimeSettings(RuntimeSettings(Accelerator.GPU))
        runtime.generateResponse("two").text()

        assertTrue(cpuEngine.closed, "the CPU engine was leaked")
        assertEquals(2, bridge.engines.size)
        assertEquals(Accelerator.GPU, bridge.engine.options.accelerator)
        assertEquals(MODEL, bridge.engine.options.modelPath)
        // The reported setting follows the engine rather than being a field that says GPU
        // while inference still runs on the CPU.
        assertEquals(RuntimeSettings(Accelerator.GPU), runtime.runtimeSettings)
    }

    @Test
    fun settingTheSameBackendDoesNotReload() = runTest {
        val runtime = runtime()
        runtime.generateResponse("one").text()

        runtime.updateRuntimeSettings(RuntimeSettings(Accelerator.CPU))

        assertEquals(1, bridge.engines.size)
        assertEquals(1, bridge.engine.conversations.size)
    }

    @Test
    fun aBackendThatCannotBeOpenedLeavesTheRuntimeUnloaded() = runTest {
        val bridge = FakeLiteRtLmBridge(unavailable = Accelerator.GPU)
        val runtime = runtime(bridge = bridge)
        runtime.generateResponse("one").text()

        val failure = assertFailsWith<IllegalStateException> {
            runtime.updateRuntimeSettings(RuntimeSettings(Accelerator.GPU))
        }
        assertTrue(failure.message!!.contains("loaded again"), failure.message!!)

        // The CPU engine is already gone, so the runtime says so instead of generating
        // through a freed handle.
        assertFailsWith<IllegalStateException> { runtime.generateResponse("two").text() }
        assertTrue(bridge.engines.single().closed)
    }

    @Test
    fun generatingAfterUnloadFails() = runTest {
        val loader = LiteRtLmModelLoader(bridge = bridge, config = ModelConfig())
        val runtime = loader.load(MODEL)
        loader.unload(MODEL)

        val failure = assertFailsWith<IllegalStateException> { runtime.generateResponse("hi").text() }
        assertTrue(failure.message!!.contains(MODEL), failure.message!!)
    }

    @Test
    fun rejectsPartsItCannotSend() = runTest {
        val runtime = runtime()

        assertFailsWith<ClassCastException> {
            runtime.generateResponse(
                listOf(
                    PromptPart.Text("What is in this picture? "),
                    PromptPart.ImageBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)),
                )
            )
        }
        // Rejected, not partially sent.
        assertTrue(bridge.engine.conversations.isEmpty())
    }

    @Test
    fun unloadWaitsForAnInFlightGeneration() = runTest {
        val loader = LiteRtLmModelLoader(bridge = bridge, config = ModelConfig())
        val runtime = loader.load(MODEL)
        // First turn opens the conversation, so the fake below is installed on a real one.
        runtime.generateResponse("warm up").text()

        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var engineWasOpen = false
        bridge.engine.conversation.whileGenerating = {
            started.complete(Unit)
            runBlocking { release.await() }
            // The unload below must not have reached the engine while this is running: the
            // handles it frees are the ones this generation is using.
            engineWasOpen = !bridge.engine.closed
        }

        val generating = launch(Dispatchers.Default) { runtime.generateResponse("slow").text() }
        started.await()
        val unloading = launch(Dispatchers.Default) { loader.unload(MODEL) }

        release.complete(Unit)
        generating.join()
        unloading.join()

        assertTrue(engineWasOpen, "the engine was freed under a running generation")
        assertTrue(bridge.engine.closed)
        assertTrue(bridge.engine.conversation.closed)
    }

    @Test
    fun startsOnTheBackendTheLoaderWasConfiguredWith() = runTest {
        val runtime = runtime(settings = RuntimeSettings(Accelerator.GPU))

        assertEquals(RuntimeSettings(Accelerator.GPU), runtime.runtimeSettings)
        assertEquals(Accelerator.GPU, bridge.engines.single().options.accelerator)
    }
}
