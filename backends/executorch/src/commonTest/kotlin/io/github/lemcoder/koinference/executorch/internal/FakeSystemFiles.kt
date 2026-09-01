package io.github.lemcoder.koinference.executorch.internal

/** A filesystem that is a set of paths, so tokenizer discovery needs no disk. */
internal class FakeSystemFiles(private val paths: Set<String>) : SystemFiles {

    override fun isFile(path: String): Boolean = path in paths
}
