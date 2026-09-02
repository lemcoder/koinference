package io.github.lemcoder.koinference.whisper.internal

import java.io.File

internal actual fun platformAudioBytes(): AudioBytes = object : AudioBytes {
    override fun read(path: String): ByteArray {
        val file = File(path)
        require(file.isFile) { "no audio file at $path" }
        return file.readBytes()
    }
}
