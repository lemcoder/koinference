package io.github.lemcoder.koinference.whisper.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.rewind

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformAudioBytes(): AudioBytes = object : AudioBytes {
    override fun read(path: String): ByteArray {
        val file = fopen(path, "rb") ?: error("no audio file at $path")
        try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file).toInt()
            rewind(file)
            require(size > 0) { "audio file is empty: $path" }

            val bytes = ByteArray(size)
            bytes.usePinned { pinned ->
                fread(pinned.addressOf(0), 1u, size.toULong(), file)
            }
            return bytes
        } finally {
            fclose(file)
        }
    }
}
