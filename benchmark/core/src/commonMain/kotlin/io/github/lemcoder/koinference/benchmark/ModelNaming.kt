package io.github.lemcoder.koinference.benchmark

/**
 * Reading a model's identity off its file name.
 *
 * A convenience for launchers that were not told the identity: `-e modelId` and `-e quantization`
 * always win, because guessing from a filename is exactly the kind of thing that silently makes
 * two runs look like the same experiment. What this is *good* for is the other direction —
 * `LFM2.5-1.2B-Instruct-Q4_0.gguf` and `LFM2.5-1.2B-Instruct_int4.litertlm` giving the same
 * modelId and differing only in quantization, which is what makes a cross-engine comparison
 * meaningful rather than decorative.
 *
 * One list of labels, used by both functions. It used to be written out twice in adjacent
 * regexes, where adding a quantization to one and not the other would leave a model id with a
 * suffix still on it and no test to notice.
 */
private val QUANTIZATION_LABELS = listOf(
    "q4_0", "q4_k_m", "q5_k_m", "q6_k", "q8_0", "int4", "int8", "f16", "bf16", "f32",
)

private val QUANTIZATION_SUFFIX =
    Regex("[-_]((?i)${QUANTIZATION_LABELS.joinToString("|")})$")

/** The file's stem: no directory, no extension. */
private fun stemOf(path: String): String =
    path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')

/** Strips the extension and any quantization suffix. */
fun modelIdOf(path: String): String = stemOf(path).replace(QUANTIZATION_SUFFIX, "")

/**
 * The label the file's producer used, taken from the name.
 *
 * "unknown" rather than a guess when the name carries none: quantization cannot be inferred from
 * a file size, and a wrong label here is worse than an absent one.
 */
fun quantizationOf(path: String): String =
    QUANTIZATION_SUFFIX.find(stemOf(path))?.groupValues?.get(1)?.lowercase() ?: "unknown"
