package io.github.lemcoder.koinference.whisper.internal

/** Audio files as a map, so a runtime test needs no disk. */
internal class FakeAudioBytes(private val files: Map<String, ByteArray>) : AudioBytes {

    val read = mutableListOf<String>()

    override fun read(path: String): ByteArray {
        read += path
        return files[path] ?: error("no audio file at $path")
    }
}
