package io.github.lemcoder.koinference.onnx.internal

import java.io.File

internal actual fun platformModelFiles(): ModelFiles = object : ModelFiles {
    override fun readText(path: String): String? =
        File(path).takeIf { it.isFile }?.readText()
}
