package io.github.lemcoder.koinference

/**
 * Flatten a prompt that must be text-only, failing on anything else.
 *
 * Both backends can currently only send text: the llama.cpp facade has no mtmd wiring, and the
 * LiteRT-LM one would need its engine created with a vision or audio backend and a model that
 * has those encoders. Rather than each of them hand-rolling the same check, they share this —
 * and share the same error, so the message is worth reading.
 *
 * @param backend name used in the error message.
 */
fun List<PromptPart>.textOnly(backend: String): String {
    val unsupported = filterNot { it is PromptPart.Text }
    if (unsupported.isNotEmpty()) {
        throw UnsupportedOperationException(
            "$backend cannot handle ${unsupported.joinToString { it::class.simpleName ?: "?" }} " +
                "in a prompt yet; only PromptPart.Text is wired up."
        )
    }
    // Several text parts are legal and ordered, so they concatenate rather than being an error.
    return filterIsInstance<PromptPart.Text>().joinToString("") { it.text }
}
