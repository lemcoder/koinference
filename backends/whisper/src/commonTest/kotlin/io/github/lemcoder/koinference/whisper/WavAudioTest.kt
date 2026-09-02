package io.github.lemcoder.koinference.whisper

import io.github.lemcoder.koinference.whisper.internal.WavAudio
import io.github.lemcoder.koinference.whisper.internal.WavFixture
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The audio decoder, tested without an engine or a file.
 *
 * This is the part most likely to meet something nobody tried, and its failures are silent: a wrong
 * conversion transcribes as noise rather than throwing. Kotlin rather than C precisely so it can be
 * checked here.
 */
class WavAudioTest {

    @Test
    fun `decodes mono 16-bit samples to the range whisper expects`() {
        val wav = WavFixture.pcm16(listOf(0, Short.MAX_VALUE, (-Short.MAX_VALUE).toShort()))

        val samples = WavAudio.decode(wav)

        assertEquals(3, samples.size)
        assertEquals(0f, samples[0])
        assertTrue(abs(samples[1] - 1f) < 1e-4, "expected +1, got ${samples[1]}")
        assertTrue(abs(samples[2] + 1f) < 1e-4, "expected -1, got ${samples[2]}")
    }

    @Test
    fun `averages stereo to mono`() {
        // whisper takes one channel; its own example averages rather than dropping one.
        val wav = WavFixture.pcm16(listOf(1000, 3000), channels = 2)

        val samples = WavAudio.decode(wav)

        assertEquals(1, samples.size)
        assertTrue(abs(samples[0] - 2000f / Short.MAX_VALUE) < 1e-4)
    }

    @Test
    fun `walks past a chunk it does not know`() {
        // ffmpeg writes LIST between fmt and data; assuming their order would break on its output.
        val wav = WavFixture.pcm16(listOf(1234), extraChunk = true)

        assertEquals(1, WavAudio.decode(wav).size)
    }

    @Test
    fun `refuses a sample rate it would have to resample`() {
        val wav = WavFixture.pcm16(listOf(1, 2), sampleRate = 44_100)

        val failure = assertFailsWith<IllegalArgumentException> { WavAudio.decode(wav) }

        // Named, because a silent resample transcribes as noise.
        assertTrue(failure.message!!.contains("44100"), failure.message!!)
        assertTrue(failure.message!!.contains("16000"), failure.message!!)
    }

    @Test
    fun `refuses formats it does not read`() {
        assertFailsWith<IllegalArgumentException> {
            WavAudio.decode(WavFixture.pcm16(listOf(1), bitsPerSample = 24))
        }
        assertFailsWith<IllegalArgumentException> {
            // 3 is IEEE float; readable in principle, and not read here.
            WavAudio.decode(WavFixture.pcm16(listOf(1), audioFormat = 3))
        }
    }

    @Test
    fun `refuses something that is not a WAV at all`() {
        assertFailsWith<IllegalArgumentException> { WavAudio.decode(ByteArray(64)) }
        assertFailsWith<IllegalArgumentException> { WavAudio.decode(ByteArray(4)) }
    }
}
