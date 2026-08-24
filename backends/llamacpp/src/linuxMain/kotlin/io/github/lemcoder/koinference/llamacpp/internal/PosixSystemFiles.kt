@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.llamacpp.internal

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen

internal object PosixSystemFiles : SystemFiles {

    /**
     * Read a whole small text file.
     *
     * Line by line rather than seeking to the end for a length: /proc and /sys report a size of
     * zero, so anything that trusts the file length reads nothing. These files are a few hundred
     * bytes.
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
