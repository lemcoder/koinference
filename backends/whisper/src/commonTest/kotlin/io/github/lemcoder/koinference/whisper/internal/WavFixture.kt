package io.github.lemcoder.koinference.whisper.internal

/**
 * Builds a WAV file in memory, so the decoder can be tested without one on disk.
 *
 * Only what the decoder accepts plus the ways it is asked to refuse — that is the whole point of it
 * being a fixture rather than a recording.
 */
internal object WavFixture {

    fun pcm16(
        samples: List<Short>,
        channels: Int = 1,
        sampleRate: Int = WavAudio.REQUIRED_SAMPLE_RATE,
        bitsPerSample: Int = 16,
        audioFormat: Int = 1,
        extraChunk: Boolean = false,
    ): ByteArray {
        val data = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, value ->
            data[index * 2] = (value.toInt() and 0xff).toByte()
            data[index * 2 + 1] = ((value.toInt() shr 8) and 0xff).toByte()
        }

        val out = mutableListOf<Byte>()
        fun ascii(text: String) = text.forEach { out += it.code.toByte() }
        fun int32(value: Int) = repeat(4) { out += ((value shr (8 * it)) and 0xff).toByte() }
        fun int16(value: Int) = repeat(2) { out += ((value shr (8 * it)) and 0xff).toByte() }

        ascii("RIFF"); int32(0); ascii("WAVE")

        ascii("fmt "); int32(16)
        int16(audioFormat); int16(channels); int32(sampleRate)
        int32(sampleRate * channels * bitsPerSample / 8)
        int16(channels * bitsPerSample / 8); int16(bitsPerSample)

        // ffmpeg writes a LIST chunk between fmt and data often enough that the decoder has to walk
        // the chunks rather than assume their order.
        if (extraChunk) {
            ascii("LIST"); int32(4); ascii("INFO")
        }

        ascii("data"); int32(data.size)
        data.forEach { out += it }

        return out.toByteArray()
    }
}
