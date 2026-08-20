@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.llamacpp.internal

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen

internal actual fun platformSystemFiles(): SystemFiles = PosixSystemFiles

private object PosixSystemFiles : SystemFiles {

    /**
     * Read a whole small text file.
     *
     * Line by line rather than by seeking to the end for a length: /proc and /sys report a size of
     * zero, so the gguf reader's approach would return nothing here. These files are a few hundred
     * bytes at most.
     */
    override fun read(path: String): String? = memScoped {
        val file = fopen(path, "r") ?: return null
        try {
            val buffer = allocArray<ByteVar>(LINE_BYTES)
            val text = StringBuilder()
            while (fgets(buffer, LINE_BYTES, file) != null) {
                text.append(buffer.toKString())
            }
            text.toString()
        } finally {
            fclose(file)
        }
    }
}

/** /proc/cpuinfo has the longest lines here, and they are well inside this. */
private const val LINE_BYTES = 512
