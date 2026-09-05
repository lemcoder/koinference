package io.github.lemcoder.koinference.executorch.internal

/**
 * Reads the one number worth keeping out of ExecuTorch's stats JSON.
 *
 * The shape is fixed by `extension/llm/runner/stats.h`, which writes a flat object of integers and
 * doubles. A regex rather than a JSON dependency for this module: one field, no nesting, and a
 * malformed line should mean "no count" rather than a parse failure inside a callback on the
 * engine's own thread.
 */
internal object ExecuTorchStats {

    private val GENERATED = Regex(""""generated_tokens"\s*:\s*(\d+)""")

    /** Tokens the engine says it generated, or null when the stats did not say. */
    fun generatedTokens(stats: String): Int? =
        GENERATED.find(stats)?.groupValues?.get(1)?.toIntOrNull()
}
