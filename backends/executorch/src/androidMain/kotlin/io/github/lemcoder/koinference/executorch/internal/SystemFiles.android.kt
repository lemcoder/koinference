package io.github.lemcoder.koinference.executorch.internal

import java.io.File

internal actual fun platformFiles(): SystemFiles = object : SystemFiles {
    override fun isFile(path: String): Boolean = File(path).isFile
}
