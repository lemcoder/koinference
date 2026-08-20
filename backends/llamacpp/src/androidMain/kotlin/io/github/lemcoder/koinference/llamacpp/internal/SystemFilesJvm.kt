package io.github.lemcoder.koinference.llamacpp.internal

import java.io.File

// Named after the binding rather than the common file, like every platform file here.
// Duplicated verbatim in jvmMain and androidMain on purpose; see docs/backends.md.
//
// The JVM leg runs on Linux and on macOS, and cannot know which at compile time. It uses the policy
// either way: on a macOS JVM the files below simply are not there, and the policy answers "do not
// pin" — the same answer the Apple leg gives outright.
internal actual fun platformCpuPlacement(): CpuPlacementSource = CpuPlacementPolicy(JvmSystemFiles)

private object JvmSystemFiles : SystemFiles {
    // readText, not readBytes: /proc and /sys report a size of zero, so anything that trusts the
    // file length reads nothing.
    override fun read(path: String): String? = runCatching { File(path).readText() }.getOrNull()
}
