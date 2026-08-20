package io.github.lemcoder.koinference.llamacpp.internal

import java.io.File

// Named after the binding rather than after the common file, like every platform file here — a
// commonMain file with top-level declarations collides with a same-named platform one.
//
// Duplicated verbatim in jvmMain and androidMain on purpose; see docs/backends.md.
internal actual fun platformSystemFiles(): SystemFiles = JvmSystemFiles

private object JvmSystemFiles : SystemFiles {
    // readText, not readBytes: /proc and /sys report a size of zero, so anything that trusts the
    // file length reads nothing.
    override fun read(path: String): String? = runCatching { File(path).readText() }.getOrNull()
}
