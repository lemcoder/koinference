package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.GenerationConstraint
import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.ModelConfig
import io.github.lemcoder.koinference.Accelerator
import io.github.lemcoder.koinference.PromptPart
import io.github.lemcoder.koinference.RuntimeSettings
import io.github.lemcoder.koinference.llamacpp.internal.DEFAULT_MIN_P
import io.github.lemcoder.koinference.llamacpp.internal.DEFAULT_TEMPERATURE
import io.github.lemcoder.koinference.llamacpp.internal.DEFAULT_TOP_K
import io.github.lemcoder.koinference.llamacpp.internal.CpuPlacementPolicy
import io.github.lemcoder.koinference.llamacpp.internal.CpuPlacementSource
import io.github.lemcoder.koinference.llamacpp.internal.FakeLlamaCppBridge
import io.github.lemcoder.koinference.llamacpp.internal.MutableMachine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val MODEL = "/models/stories.gguf"

/**
 * The llama.cpp counterpart of `LiteRtLmRuntimeTest`, and the thing that was unreachable while
 * the seam was a set of top-level `expect fun`s: none of this needs a GGUF file.
 */
class LlamaCppRuntimeTest {

    private val bridge = FakeLlamaCppBridge()

    private suspend fun runtime(
        parameters: GenerationParameters = GenerationParameters(),
        settings: RuntimeSettings = RuntimeSettings(),
        bridge: FakeLlamaCppBridge = this.bridge,
    ): LlamaCppTextRuntime = loader(parameters, settings, bridge).load(MODEL)

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

    @Test
    fun reusesOneSessionAcrossTurns() = runTest {
        val runtime = runtime()

        runtime.generateResponse("one")
        runtime.generateResponse("two")

        assertEquals(1, bridge.model.sessions.size)
        assertEquals(listOf("one", "two"), bridge.model.session.turns.map { it.prompt })
    }

    @Test
    fun opensTheSessionWithTheParametersItWasGiven() = runTest {
        runtime(GenerationParameters(topK = 7, minP = 0.2, temperature = 0.1))
            .generateResponse("hello")

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
        runtime().generateResponse("hello")

        val options = bridge.model.session.options
        assertEquals(DEFAULT_TOP_K, options.topK)
        assertEquals(DEFAULT_MIN_P, options.minP)
        assertEquals(DEFAULT_TEMPERATURE, options.temperature)
    }

    @Test
    fun topPIsNotPassedOffAsMinP() = runTest {
        // koi_session_create takes no top-p, so a caller asking for one is ignored rather than
        // surprised by min-p standing in for it.
        runtime(GenerationParameters(topP = 0.5)).generateResponse("hello")

        assertEquals(DEFAULT_MIN_P, bridge.model.session.options.minP)
    }

    @Test
    fun sendsTheSystemPromptWithEveryTurn() = runTest {
        val runtime = runtime()

        runtime.generateResponse("one")
        runtime.generateResponse("two")

        assertTrue(bridge.model.session.turns.all { it.systemPrompt == "You are terse." })
    }

    @Test
    fun convertsASchemaToAGrammarAndPassesItDown() = runTest {
        val schema = """{"type":"object"}"""

        runtime().generateResponse("hello", GenerationConstraint.JsonSchema(schema))

        assertEquals("grammar for $schema", bridge.model.session.turns.single().grammar)
    }

    @Test
    fun anUnconvertibleSchemaIsRejectedRatherThanGeneratingUnconstrained() = runTest {
        bridge.unconvertibleSchemas = setOf("nonsense")
        val runtime = runtime()

        assertFailsWith<IllegalArgumentException> {
            runtime.generateResponse("hello", GenerationConstraint.JsonSchema("nonsense"))
        }
        // Nothing was generated: an unconstrained reply would look like a working schema.
        assertTrue(bridge.model.sessions.all { it.turns.isEmpty() })
    }

    @Test
    fun anEmptyReplyIsRaisedRatherThanReturned() = runTest {
        // A -1 from the facade reaches Kotlin as an empty string, which is indistinguishable
        // from a model that had nothing to say — so it is raised instead of returned.
        bridge.reply = { "" }
        val runtime = runtime()

        val failure = assertFailsWith<IllegalStateException> { runtime.generateResponse("hello") }
        assertTrue(failure.message!!.contains(MODEL), failure.message!!)
    }

    @Test
    fun changingParametersRebuildsTheSessionButKeepsTheWeights() = runTest {
        val runtime = runtime()
        runtime.generateResponse("one")
        val first = bridge.model.session

        runtime.updateGenerationParameters(GenerationParameters(topK = 1))
        runtime.generateResponse("two")

        assertTrue(first.closed, "the old session was leaked")
        assertEquals(2, bridge.model.sessions.size)
        assertEquals(1, bridge.model.session.options.topK)
        assertEquals(1, bridge.models.size, "the weights should not have been reloaded")
        assertEquals(GenerationParameters(topK = 1), runtime.generationParameters)
    }

    @Test
    fun settingTheSameParametersKeepsTheSession() = runTest {
        val runtime = runtime(GenerationParameters(topK = 1))
        runtime.generateResponse("one")

        runtime.updateGenerationParameters(GenerationParameters(topK = 1))
        runtime.generateResponse("two")

        assertEquals(1, bridge.model.sessions.size, "the KV cache was dropped for nothing")
    }

    @Test
    fun changingTheBackendReloadsTheModelOnIt() = runTest {
        val runtime = runtime()
        runtime.generateResponse("one")
        val cpuModel = bridge.model

        runtime.updateRuntimeSettings(RuntimeSettings(Accelerator.GPU))
        runtime.generateResponse("two")

        assertTrue(cpuModel.closed, "the CPU model was leaked")
        assertEquals(2, bridge.models.size)
        assertEquals(Accelerator.GPU, bridge.model.options.accelerator)
        assertEquals(MODEL, bridge.model.options.modelPath)
        // The reported setting follows the model rather than being a field that says GPU while
        // inference still runs on the CPU.
        assertEquals(RuntimeSettings(Accelerator.GPU), runtime.runtimeSettings)
    }

    @Test
    fun settingTheSameBackendDoesNotReload() = runTest {
        val runtime = runtime()
        runtime.generateResponse("one")

        runtime.updateRuntimeSettings(RuntimeSettings(Accelerator.CPU))

        assertEquals(1, bridge.models.size)
        assertEquals(1, bridge.model.sessions.size)
    }

    @Test
    fun aBackendThatCannotBeOpenedLeavesTheRuntimeUnloaded() = runTest {
        val bridge = FakeLlamaCppBridge(unavailable = Accelerator.GPU)
        val runtime = runtime(bridge = bridge)
        runtime.generateResponse("one")

        val failure = assertFailsWith<IllegalStateException> {
            runtime.updateRuntimeSettings(RuntimeSettings(Accelerator.GPU))
        }
        assertTrue(failure.message!!.contains("loaded again"), failure.message!!)

        // The CPU model is already gone, so the runtime says so instead of generating through a
        // freed handle.
        assertFailsWith<IllegalStateException> { runtime.generateResponse("two") }
        assertTrue(bridge.models.single().closed)
    }

    @Test
    fun startsOnTheBackendTheLoaderWasConfiguredWith() = runTest {
        val runtime = runtime(settings = RuntimeSettings(Accelerator.GPU))

        assertEquals(RuntimeSettings(Accelerator.GPU), runtime.runtimeSettings)
        assertEquals(Accelerator.GPU, bridge.models.single().options.accelerator)
    }

    @Test
    fun generatingAfterUnloadFails() = runTest {
        val loader = loader()
        val runtime = loader.load(MODEL)
        loader.unload(MODEL)

        val failure = assertFailsWith<IllegalStateException> { runtime.generateResponse("hi") }
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
                ),
            )
        }
        // Rejected, not partially sent.
        assertTrue(bridge.model.sessions.isEmpty())
    }

    @Test
    fun streamsChunksAndConcatenatesToTheBlockingReply() = runTest {
        val runtime = runtime()

        val streamed = runtime.streamResponse("hello").toList()
        val blocking = runtime.generateResponse("hello")

        assertTrue(streamed.size > 1, "a stream that arrives in one piece is not a stream")
        assertEquals(blocking, streamed.joinToString(""))
    }

    @Test
    fun abandoningAStreamEndsTheGeneration() = runTest {
        val runtime = runtime()

        // Takes one chunk and walks away; the session must not be left mid-decode for the next
        // caller.
        runtime.streamResponse("hello").first()

        assertTrue(bridge.model.session.streamEnded, "the generation was left open")
    }

    @Test
    fun aStreamHoldsTheRuntimeSoASecondTurnCannotInterleave() = runTest {
        val runtime = runtime()

        val chunks = runtime.streamResponse("first").toList()

        // The second turn ran only after the first flow completed, so both are whole turns
        // rather than two generations decoding into one KV cache.
        runtime.generateResponse("second")
        assertTrue(chunks.isNotEmpty())
        assertEquals(listOf("first", "second"), bridge.model.session.turns.map { it.prompt })
    }

    @Test
    fun countsTokensWithTheModelsOwnTokenizer() = runTest {
        val runtime = runtime()

        assertEquals(3, runtime.countTokens("one two three"))
        // The count reuses the session a generation would use rather than opening a second one.
        assertEquals(1, bridge.model.sessions.size)
    }

    @Test
    fun unloadWaitsForAnInFlightGeneration() = runTest {
        val loader = loader()
        val runtime = loader.load(MODEL)
        // First turn opens the session, so the hook below is installed on a real one.
        runtime.generateResponse("warm up")

        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var modelWasOpen = false
        bridge.model.session.whileGenerating = {
            started.complete(Unit)
            runBlocking { release.await() }
            // The unload below must not have reached the model while this is running: the
            // handles it frees are the ones this generation is using.
            modelWasOpen = !bridge.model.closed
        }

        val generating = launch(Dispatchers.Default) { runtime.generateResponse("slow") }
        started.await()
        val unloading = launch(Dispatchers.Default) { loader.unload(MODEL) }

        release.complete(Unit)
        generating.join()
        unloading.join()

        assertTrue(modelWasOpen, "the weights were freed under a running generation")
        assertTrue(bridge.model.closed)
        assertTrue(bridge.model.session.closed)
    }

    @Test
    fun placesTheThreadsBeforeDecodingAndOnlyOnce() = runTest {
        val runtime = runtime()

        runtime.generateResponse("one")
        runtime.generateResponse("two")

        // Chosen before the first decode, and not re-applied when the machine has not changed.
        assertEquals(listOf(listOf(4, 5, 6, 7)), bridge.model.session.maskHistory)
    }

    @Test
    fun repinsWhenTheUsableCpusChange() = runTest {
        // A backgrounded app is confined to the little cluster, so a mask over the big one names
        // cores it may no longer touch.
        val machine = MutableMachine()
        val runtime = loader(placementPolicy = CpuPlacementPolicy(machine)).load(MODEL)

        runtime.generateResponse("foreground")
        machine.permitted = "0-3"
        runtime.generateResponse("background")

        assertEquals(
            listOf(listOf(4, 5, 6, 7), emptyList()),
            bridge.model.session.maskHistory,
        )
    }

    @Test
    fun aNewSessionIsPlacedAgain() = runTest {
        val runtime = runtime()
        runtime.generateResponse("one")

        // The parameter change rebuilds the session, and a fresh pool starts unplaced.
        runtime.updateGenerationParameters(GenerationParameters(topK = 1))
        runtime.generateResponse("two")

        assertEquals(listOf(listOf(4, 5, 6, 7)), bridge.model.session.maskHistory)
    }
}

