package io.github.lemcoder.koinference.executorch.internal

/**
 * The bits of the filesystem this backend reads.
 *
 * An interface so tokenizer discovery is testable without laying files on disk — the same reason
 * the bridge is one.
 */
internal interface SystemFiles {

    fun isFile(path: String): Boolean
}

/** The real filesystem. */
internal expect fun platformFiles(): SystemFiles
