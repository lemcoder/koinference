package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.Accelerator
import io.github.lemcoder.koinference.runtime.AudioFormat
import io.github.lemcoder.koinference.runtime.Modality
import io.github.lemcoder.koinference.runtime.ResponsePart
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Does the architecture hold for a model whose reply carries two modalities at once?
 *
 * This is the case that broke the previous design. Runtimes were split by output type —
 * `generateResponse(): String` and an `ImageRuntime` returning a single image — so a model that
 * interleaves speech with its transcript had no interface it could implement. `Modality` was already
 * a `Set` and would have allowed `setOf(TEXT, AUDIO)`; there was simply nothing to implement.
 *
 * `FakeOmniBackend` is written as if it were such an engine. What it needs from `:core` is a
 * `Modality` constant and nothing else: `Backend`, `ModelLoader`, `ModelConfig`, `GeneratingRuntime`,
 * the settings surface and `PromptPart` are all reused as a text engine uses them.
 */
class MultiModalityTest {

    private val text = FakeBackend("llama.cpp", listOf(".gguf"))
    private val omni = FakeOmniBackend()
    private val koi = Koinference(text, omni)

    @Test
    fun aReplyCanInterleaveTextAndAudio() = runTest {
        val reply = koi.load("/m/qwen.omni").generateResponse("say hello")

        // The ordering is the point: a shape that returned text and audio separately would lose it.
        assertEquals(
            listOf("Text", "Audio", "Text", "Audio"),
            reply.map { it::class.simpleName },
        )
        assertEquals("Hello there", reply.text())
    }

    @Test
    fun interleavingSurvivesStreaming() = runTest {
        val parts = koi.load("/m/qwen.omni").streamResponse("say hello").toList()

        assertEquals(4, parts.size)
        val audio = parts.filterIsInstance<ResponsePart.Audio>()
        assertEquals(AudioFormat.PCM_16, audio.first().format)
        assertEquals(24_000, audio.first().sampleRateHz)
    }

    @Test
    fun aTextOnlyEngineIsTheSameShapeWithOneKindOfPart() = runTest {
        val reply = koi.load("/m/a.gguf").generateResponse("hi")

        // No special case anywhere: a text engine simply never emits anything but Text.
        assertTrue(reply.all { it is ResponsePart.Text })
        assertEquals("reply from /m/a.gguf", reply.text())
    }

    @Test
    fun oneLoadServesBothKindsOfModel() = runTest {
        // There is no loadText/loadVision to choose between, because there is nothing to choose:
        // every generating runtime speaks ResponsePart.
        assertEquals("reply from /m/a.gguf", koi.load("/m/a.gguf").generateResponse("hi").text())
        assertEquals("Hello there", koi.load("/m/qwen.omni").generateResponse("hi").text())
    }

    @Test
    fun aBackendDeclaresEveryModalityItsRepliesCarry() {
        assertEquals(setOf(Modality.TEXT, Modality.AUDIO), omni.modalities)
        assertEquals(setOf(Modality.TEXT), text.modalities)
    }

    @Test
    fun theSettingsSurfaceIsSharedAcrossModalities() = runTest {
        // An omni model has a device and a sampler like anything else, which is why those members
        // are on ModelRuntime rather than on a per-modality interface.
        val runtime = koi.load("/m/qwen.omni")

        runtime.updateRuntimeSettings(RuntimeSettings(Accelerator.GPU))

        assertEquals(Accelerator.GPU, runtime.runtimeSettings.accelerator)
    }

    @Test
    fun aPromptCanCarryAudioIntoAnOmniModel() = runTest {
        // PromptPart needed no change: it has carried AudioFile from the start, which is why the
        // input side was never the problem.
        val reply = koi.load("/m/qwen.omni").generateResponse(
            listOf(PromptPart.Text("reply to this: "), PromptPart.AudioFile("/a/question.wav")),
        )

        assertEquals("Hello there", reply.text())
    }
}
