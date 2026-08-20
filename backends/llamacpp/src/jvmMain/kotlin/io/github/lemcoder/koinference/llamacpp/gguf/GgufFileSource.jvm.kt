package io.github.lemcoder.koinference.llamacpp.gguf

import java.io.File

internal actual fun readFileBytes(path: String): ByteArray = File(path).readBytes()
