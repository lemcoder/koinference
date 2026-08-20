package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.Modality
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import io.github.lemcoder.koinference.runtime.Accelerator
import io.github.lemcoder.koinference.runtime.vision.ImageFormat
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Does the architecture hold when a second modality arrives?
 *
 * `FakeVisionBackend` is written as if it were a real engine for a modality this repository has no
 * engine for. What it needed from `:core` is the answer: a `Modality`, a runtime interface for its
 * output, and nothing else. `Backend`, `ModelLoader`, `ModelConfig`, `RuntimeGuard`, `PromptPart`
 * and the whole settings surface were reused unchanged.
 *
 * The one thing that did have to change is recorded here too: `load` could no longer promise text.
 */
class MultiModalityTest {

    private val text = FakeBackend("llama.cpp", listOf(".gguf"))
    private val vision = FakeVisionBackend()
    private val koi = Koinference(text, vision)

    @Test
    fun bothModalitiesLoadThroughOneEntryPoint() = runTest {
        assertEquals("reply from /m/a.gguf", koi.loadText("/m/a.gguf").generateResponse("hi"))

        val image = koi.loadVision("/m/sd.safetensors").generateImage("a cat")
        assertEquals(ImageFormat.PNG, image.format)
        assertEquals(8, image.width)
    }

    @Test
    fun askingForTheWrongModalityFailsBeforeReadingWeights() = runTest {
        // The check is against the declared modality, not the runtime that comes back, so a caller
        // gets told for free rather than after several hundred megabytes.
        val failure = assertFailsWith<IllegalStateException> { koi.loadText("/m/sd.safetensors") }

        assertTrue(failure.message!!.contains("fake-diffusion"), failure.message!!)
        assertTrue(failure.message!!.contains("TEXT"), failure.message!!)
        assertTrue(vision.loaders.isEmpty(), "must not construct a loader for a refused modality")
    }

    @Test
    fun theSettingsSurfaceIsSharedAcrossModalities() = runTest {
        // The whole ModelRuntime contract applies unchanged to an image model: it has a device and
        // a sampler like anything else, which is why those members are not on the text interfaces.
        val runtime = koi.loadVision("/m/sd.safetensors")

        runtime.updateRuntimeSettings(RuntimeSettings(Accelerator.GPU))

        assertEquals(Accelerator.GPU, runtime.runtimeSettings.accelerator)
    }

    @Test
    fun aPromptCanCarryImagesIntoEitherModality() = runTest {
        // PromptPart needed no change: it has carried ImageFile and ImageBytes from the start, which
        // is why a vision-language model answering in words is still TEXT.
        val runtime = koi.loadVision("/m/sd.safetensors")

        val image = runtime.generateImage(
            listOf(PromptPart.Text("in this style: "), PromptPart.ImageFile("/img/ref.png")),
        )

        assertEquals(ImageFormat.PNG, image.format)
    }

    @Test
    fun anImageRuntimeRejectsPartsItCannotUse() = runTest {
        val runtime = koi.loadVision("/m/sd.safetensors")

        assertFailsWith<UnsupportedOperationException> {
            runtime.generateImage(listOf(PromptPart.AudioFile("/a.wav")))
        }
    }

    @Test
    fun aBackendDeclaresWhichKnobsItAppliesRegardlessOfModality() {
        // honours was built for text sampling and needed no change: a diffusion model has a seed
        // and no top-k, which the same set expresses.
        assertEquals(setOf(Modality.IMAGE), vision.modalities)
        assertTrue(vision.honours.isNotEmpty())
    }

    @Test
    fun theBaseLoadStillWorksWithoutKnowingTheModality() = runTest {
        // Useful for a caller that only wants to retune or unload: no cast, no modality needed.
        val runtime = koi.load("/m/sd.safetensors")

        assertEquals(Accelerator.CPU, runtime.runtimeSettings.accelerator)
        koi.unloadAll()
    }
}
