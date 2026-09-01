package io.github.lemcoder.koinference.whisper.internal

/**
 * Turns a WAV file into the samples whisper wants: mono 16 kHz floats in [-1, 1].
 *
 * Kotlin rather than C, because it can be: this is parsing and arithmetic, and every rule that ends
 * up in C has to be kept in step with a Kotlin one. It is also the part most likely to meet a file
 * nobody tested, so it is worth having tests that do not need an engine.
 *
 * Deliberately narrow. 16-bit PCM only, because that is what `ffmpeg -ar 16000 -ac 1 -c:a pcm_s16le`
 * produces and what whisper's own examples use. Anything else fails saying what it found rather
 * than resampling or guessing at a format — a wrong conversion here is silent, and shows up as a
 * transcript of noise.
 */
internal object WavAudio {

    /** What whisper is trained on; anything else has to be resampled before it gets here. */
    const val REQUIRED_SAMPLE_RATE = 16_000

    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size >= HEADER_MINIMUM) { "not a WAV file: ${bytes.size} bytes" }
        require(tag(bytes, 0) == "RIFF" && tag(bytes, 8) == "WAVE") {
            "not a WAV file: no RIFF/WAVE header"
        }

        var format: Format? = null
        var offset = 12

        // Walk the chunks rather than assuming fmt comes first and data second: files written by
        // ffmpeg carry a LIST chunk in between often enough to matter.
        while (offset + 8 <= bytes.size) {
            val id = tag(bytes, offset)
            val size = int32(bytes, offset + 4)
            val body = offset + 8

            when (id) {
                "fmt " -> format = readFormat(bytes, body)
                "data" -> {
                    val current = requireNotNull(format) { "WAV data chunk before its fmt chunk" }
                    val end = minOf(body + size, bytes.size)
                    return samples(bytes, body, end, current)
                }
            }

            // Chunks are word aligned: an odd size is followed by a pad byte.
            offset = body + size + (size and 1)
        }

        error("WAV file has no data chunk")
    }

    private fun readFormat(bytes: ByteArray, body: Int): Format {
        val audioFormat = int16(bytes, body)
        val channels = int16(bytes, body + 2)
        val sampleRate = int32(bytes, body + 4)
        val bitsPerSample = int16(bytes, body + 14)

        require(audioFormat == PCM) {
            "WAV is format $audioFormat; whisper needs uncompressed PCM (1)"
        }
        require(bitsPerSample == 16) {
            "WAV is $bitsPerSample-bit; this backend reads 16-bit PCM only"
        }
        require(sampleRate == REQUIRED_SAMPLE_RATE) {
            "WAV is ${sampleRate}Hz; whisper needs ${REQUIRED_SAMPLE_RATE}Hz — resample it first, " +
                "because guessing at a conversion here would be silent"
        }
        require(channels in 1..2) { "WAV has $channels channels; expected mono or stereo" }

        return Format(channels)
    }

    /** Stereo is averaged to mono, which is what whisper's own example does. */
    private fun samples(bytes: ByteArray, from: Int, to: Int, format: Format): FloatArray {
        val frames = (to - from) / (2 * format.channels)
        val out = FloatArray(frames)

        var position = from
        for (frame in 0 until frames) {
            var sum = 0
            repeat(format.channels) {
                sum += int16Signed(bytes, position)
                position += 2
            }
            out[frame] = (sum.toFloat() / format.channels) / Short.MAX_VALUE.toFloat()
        }
        return out
    }

    private fun tag(bytes: ByteArray, at: Int): String =
        buildString { for (i in 0 until 4) append(bytes[at + i].toInt().toChar()) }

    private fun int32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xff) or
            ((bytes[at + 1].toInt() and 0xff) shl 8) or
            ((bytes[at + 2].toInt() and 0xff) shl 16) or
            ((bytes[at + 3].toInt() and 0xff) shl 24)

    private fun int16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)

    private fun int16Signed(bytes: ByteArray, at: Int): Int = int16(bytes, at).toShort().toInt()

    private data class Format(val channels: Int)

    private const val PCM = 1
    private const val HEADER_MINIMUM = 44
}
