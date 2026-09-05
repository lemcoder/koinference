package io.github.lemcoder.koinference.executorch.internal

/**
 * Finds the tokenizer that belongs to a `.pte`.
 *
 * ExecuTorch splits them: the program is exported to `model.pte` and the vocabulary stays in a file
 * beside it, so `LlmModule` takes two paths where every other backend in this repository takes one.
 * `ModelConfig` has one path, and adding a second field to `:core` for one engine's file layout
 * would make every other backend carry a field it has no use for.
 *
 * So it is a convention instead, applied here and nowhere else: the tokenizer is the first of
 * [CANDIDATES] sitting next to the model. A run that cannot find one fails naming what it looked
 * for, because the alternative is a native crash inside `LlmModule`'s constructor.
 */
internal object TokenizerFile {

    /**
     * Names ExecuTorch's own examples produce, most specific first.
     *
     * `<model>.tokenizer.*` before the bare names, so a directory holding two exported models does
     * not hand both of them the same vocabulary.
     */
    val CANDIDATES: List<String> = listOf(
        "tokenizer.model",
        "tokenizer.bin",
        "tokenizer.json",
    )

    /** The tokenizer for [modelPath], or null when none of the conventional names is there. */
    fun beside(modelPath: String, files: SystemFiles): String? {
        val directory = modelPath.substringBeforeLast('/', "")
        val stem = modelPath.substringAfterLast('/').substringBeforeLast('.')

        val named = CANDIDATES.map { "$directory/$stem.$it" }
        val bare = CANDIDATES.map { "$directory/$it" }

        return (named + bare).firstOrNull { files.isFile(it) }
    }

    /** Names looked for, for a failure message that says where to put the file. */
    fun searched(modelPath: String): List<String> {
        val directory = modelPath.substringBeforeLast('/', "")
        val stem = modelPath.substringAfterLast('/').substringBeforeLast('.')
        return CANDIDATES.map { "$directory/$stem.$it" } + CANDIDATES.map { "$directory/$it" }
    }
}
