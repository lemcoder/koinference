package io.github.lemcoder.koinference.whisper

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.Accelerator
import io.github.lemcoder.koinference.runtime.GenerationConstraint
import io.github.lemcoder.koinference.runtime.ResponsePart
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import io.github.lemcoder.koinference.whisper.internal.FakeAudioBytes
import io.github.lemcoder.koinference.whisper.internal.FakeWhisperBridge
import io.github.lemcoder.koinference.whisper.internal.WavFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * The first backend here that takes audio in, checked without an engine.
 *
 * What it proves beyond this backend: `PromptPart.AudioFile` reaches a real runtime and comes back
 * as `ResponsePart.Text`, so the parts-based seam carries a modality no engine could exercise until
 * now.
 */
class WhisperRuntimeTest {

    private val bridge = FakeWhisperBridge()
    private val wav = WavFixture.pcm16(List(1600) { 0 })
    private val audio = FakeAudioBytes(mapOf("/a/clip.wav" to wav))

    private fun loader(config: ModelConfig = ModelConfig()) =
        WhisperModelLoader(bridge = bridge, config = config, audio = audio)

    private suspend fun runtime(config: ModelConfig = ModelConfig()) =
        loader(config).load("/m/ggml-tiny.bin")

    @Test
    fun `transcribes audio named by path`() = runTest {
        val reply = runtime().generateResponse(listOf(PromptPart.AudioFile("/a/clip.wav")))

        assertTrue(reply.all { it is ResponsePart.Text })
        assertEquals("transcript of 1600 samples", (reply.single() as ResponsePart.Text).text)
        assertEquals(listOf("/a/clip.wav"), audio.read)
    }

    @Test
    fun `transcribes audio handed over as bytes`() = runTest {
        val reply = runtime().generateResponse(listOf(PromptPart.AudioBytes(wav)))

        assertEquals("transcript of 1600 samples", (reply.single() as ResponsePart.Text).text)
        assertTrue(audio.read.isEmpty(), "bytes in hand need no file read")
    }

    @Test
    fun `a stream arrives segment by segment`() = runTest {
        val parts = runtime().streamResponse(listOf(PromptPart.AudioFile("/a/clip.wav")))
            .toList().filterIsInstance<ResponsePart.Text>()

        assertTrue(parts.size > 1, "expected segments, got ${parts.size}")
        assertEquals("transcript of 1600 samples ", parts.joinToString("") { it.text })
    }

    @Test
    fun `several parts are one recording`() = runTest {
        runtime().generateResponse(
            listOf(PromptPart.AudioFile("/a/clip.wav"), PromptPart.AudioBytes(wav)),
        )

        // Concatenated, which is what splitting a long recording across parts should mean.
        assertEquals(3200, bridge.model.transcribed.single().size)
    }

    @Test
    fun `text in the prompt is refused rather than ignored`() = runTest {
        val failure = assertFailsWith<IllegalStateException> {
            runtime().generateResponse("say something")
        }

        assertTrue(failure.message!!.contains("audio"), failure.message!!)
    }

    @Test
    fun `an empty prompt says what was missing`() = runTest {
        assertFailsWith<IllegalArgumentException> { runtime().generateResponse(emptyList()) }
    }

    @Test
    fun `a constraint is refused rather than silently dropped`() = runTest {
        assertFailsWith<IllegalStateException> {
            runtime().generateResponse(
                listOf(PromptPart.AudioFile("/a/clip.wav")),
                GenerationConstraint.JsonSchema("{}"),
            )
        }
    }

    @Test
    fun `changing the device reloads the weights`() = runTest {
        val runtime = runtime(ModelConfig(settings = RuntimeSettings(accelerator = Accelerator.CPU)))

        runtime.updateRuntimeSettings(RuntimeSettings(accelerator = Accelerator.GPU))

        // whisper fixes its backend when the weights are loaded, so this cannot be cheaper.
        assertEquals(2, bridge.models.size)
        assertTrue(bridge.model.options.useGpu)
        assertEquals(Accelerator.GPU, runtime.runtimeSettings.accelerator)
    }

    @Test
    fun `an unloaded runtime refuses to be used`() = runTest {
        val loader = loader()
        val runtime = loader.load("/m/ggml-tiny.bin")
        runtime.generateResponse(listOf(PromptPart.AudioFile("/a/clip.wav")))

        loader.unload("/m/ggml-tiny.bin")

        assertFailsWith<IllegalStateException> {
            runtime.generateResponse(listOf(PromptPart.AudioFile("/a/clip.wav")))
        }
        assertTrue(bridge.model.closed)
    }
}
