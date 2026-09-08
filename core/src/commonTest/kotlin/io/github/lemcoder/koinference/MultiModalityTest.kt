package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.GeneratingConnection
import io.github.lemcoder.koinference.runtime.media.AudioFormat
import io.github.lemcoder.koinference.runtime.media.Modality
import io.github.lemcoder.koinference.runtime.media.ResponsePart
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Does the architecture hold for a model whose reply carries two modalities at once?
 *
 * The case that broke the previous design. `FakeOmniBackend` is written as if it were such an
 * engine; what it needs from `:core` is a `Modality` constant and nothing else — `Backend`,
 * `ModelLoader`, `Model`, `GeneratingConnection` and `PromptPart` are reused as a text engine uses them.
 */
class MultiModalityTest {

    private val text = FakeBackend("llama.cpp", listOf(".gguf"))
    private val omni = FakeOmniBackend()
    private val koi = Koinference(text, omni)

    private suspend fun connect(path: String): GeneratingConnection =
        koi.openConnection(koi.loadModel(path)) as GeneratingConnection

    @Test
    fun aReplyCanInterleaveTextAndAudio() = runTest {
        val reply = connect("/m/qwen.omni").generateAll("say hello")
        assertEquals(listOf("Text", "Audio", "Text", "Audio"), reply.map { it::class.simpleName })
        assertEquals("Hello there", reply.text())
    }

    @Test
    fun interleavingSurvivesStreaming() = runTest {
        val parts = mutableListOf<ResponsePart>()
        connect("/m/qwen.omni").generate("say hello") { parts += it }
        assertEquals(4, parts.size)
        val audio = parts.filterIsInstance<ResponsePart.Audio>()
        assertEquals(AudioFormat.PCM_16, audio.first().format)
        assertEquals(24_000, audio.first().sampleRateHz)
    }

    @Test
    fun aTextOnlyEngineIsTheSameShapeWithOneKindOfPart() = runTest {
        val reply = connect("/m/a.gguf").generateAll("hi")
        assertTrue(reply.all { it is ResponsePart.Text })
        assertEquals("reply from /m/a.gguf", reply.text())
    }

    @Test
    fun oneOpenServesBothKindsOfModel() = runTest {
        assertEquals("reply from /m/a.gguf", connect("/m/a.gguf").generateAll("hi").text())
        assertEquals("Hello there", connect("/m/qwen.omni").generateAll("hi").text())
    }

    @Test
    fun aBackendDeclaresEveryModalityItsRepliesCarry() {
        assertEquals(setOf(Modality.TEXT, Modality.AUDIO), omni.modalities)
        assertEquals(setOf(Modality.TEXT), text.modalities)
    }

    @Test
    fun aPromptCanCarryAudioIntoAnOmniModel() = runTest {
        val reply = connect("/m/qwen.omni").generateAll(
            listOf(PromptPart.Text("reply to this: "), PromptPart.AudioFile("/a/question.wav")),
        )
        assertEquals("Hello there", reply.text())
    }
}
