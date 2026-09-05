package io.github.lemcoder.koinference.onnx.internal

/**
 * The files that sit beside an ONNX graph: `vocab.txt`, and the pooling config.
 *
 * An interface so vocabulary loading and pooling discovery are testable without a model on disk.
 */
internal interface ModelFiles {

    /** The file's text, or null when it is not there. */
    fun readText(path: String): String?
}

internal expect fun platformModelFiles(): ModelFiles
