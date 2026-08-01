@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.llamacpp.gguf

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.rewind

internal actual fun readFileBytes(path: String): ByteArray {
    val file = fopen(path, "rb") ?: throw IllegalArgumentException("Cannot open file: $path")
    return try {
        fseek(file, 0, SEEK_END)
        val size = ftell(file)
        rewind(file)
        ByteArray(size.toInt()).also { buf ->
            if (buf.isNotEmpty()) {
                buf.usePinned { pinned ->
                    fread(pinned.addressOf(0), 1u, size.toULong(), file)
                }
            }
        }
    } finally {
        fclose(file)
    }
}
