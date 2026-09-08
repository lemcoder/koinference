package io.github.lemcoder.koinference.whisper

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.GeneratingConnection
import io.github.lemcoder.koinference.runtime.KoinferenceException
import io.github.lemcoder.koinference.runtime.generation.GenerationConstraint
import io.github.lemcoder.koinference.runtime.media.ResponsePart
import io.github.lemcoder.koinference.whisper.internal.FakeAudioBytes
import io.github.lemcoder.koinference.whisper.internal.FakeWhisperBridge
import io.github.lemcoder.koinference.whisper.internal.WavFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class WhisperConnectionTest {

    private val bridge = FakeWhisperBridge()
    private val wav = WavFixture.pcm16(List(1600) { 0 })
    private val audio = FakeAudioBytes(mapOf("/a/clip.wav" to wav))

    private fun loader(config: ModelConfig = ModelConfig()) =
        WhisperModelLoader(bridge = bridge, config = config, audio = audio)

    private suspend fun connection(config: ModelConfig = ModelConfig()): GeneratingConnection =
        loader(config).load("/m/ggml-tiny.bin").open() as GeneratingConnection

    @Test
    fun `transcribes audio named by path`() = runTest {
        val reply = connection().generateAll(listOf(PromptPart.AudioFile("/a/clip.wav")))
        assertTrue(reply.all { it is ResponsePart.Text })
        assertEquals("transcript of 1600 samples ", reply.filterIsInstance<ResponsePart.Text>().joinToString("") { it.text })
        assertEquals(listOf("/a/clip.wav"), audio.read)
    }

    @Test
    fun `transcribes audio handed over as bytes`() = runTest {
        val reply = connection().generateAll(listOf(PromptPart.AudioBytes(wav)))
        assertEquals("transcript of 1600 samples ", reply.filterIsInstance<ResponsePart.Text>().joinToString("") { it.text })
        assertTrue(audio.read.isEmpty(), "bytes in hand need no file read")
    }

    @Test
    fun `a stream arrives segment by segment`() = runTest {
        val parts = mutableListOf<String>()
        connection().generate(listOf(PromptPart.AudioFile("/a/clip.wav"))) {
            if (it is ResponsePart.Text) parts += it.text
        }
        assertTrue(parts.size > 1, "expected segments, got ${parts.size}")
        assertEquals("transcript of 1600 samples ", parts.joinToString(""))
    }

    @Test
    fun `several parts are one recording`() = runTest {
        connection().generateAll(listOf(PromptPart.AudioFile("/a/clip.wav"), PromptPart.AudioBytes(wav)))
        assertEquals(3200, bridge.model.transcribed.single().size)
    }

    @Test
    fun `text in the prompt is refused rather than ignored`() = runTest {
        val failure = assertFailsWith<IllegalStateException> {
            connection().generateAll(listOf(PromptPart.Text("say something")))
        }
        assertTrue(failure.message!!.contains("audio"), failure.message!!)
    }

    @Test
    fun `an empty prompt says what was missing`() = runTest {
        assertFailsWith<IllegalArgumentException> { connection().generateAll(emptyList()) }
    }

    @Test
    fun `a constraint is refused rather than silently dropped`() = runTest {
        assertFailsWith<IllegalStateException> {
            connection().generateAll(
                listOf(PromptPart.AudioFile("/a/clip.wav")),
                GenerationConstraint.JsonSchema("{}"),
            )
        }
    }

    @Test
    fun `a closed connection refuses to be used`() = runTest {
        val conn = connection()
        conn.generateAll(listOf(PromptPart.AudioFile("/a/clip.wav")))
        conn.close()
        assertFailsWith<KoinferenceException.ConnectionClosed> {
            conn.generateAll(listOf(PromptPart.AudioFile("/a/clip.wav")))
        }
    }
}
