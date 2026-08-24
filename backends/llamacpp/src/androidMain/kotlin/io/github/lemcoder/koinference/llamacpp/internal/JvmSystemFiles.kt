package io.github.lemcoder.koinference.llamacpp.internal

import java.io.File

internal object JvmSystemFiles : SystemFiles {
    // readText, not readBytes: /proc and /sys report a size of zero, so anything that trusts the
    // file length reads nothing.
    override fun read(path: String): String? = runCatching { File(path).readText() }.getOrNull()
}
