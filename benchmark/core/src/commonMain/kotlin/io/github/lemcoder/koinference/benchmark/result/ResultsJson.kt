package io.github.lemcoder.koinference.benchmark.result

import kotlinx.serialization.json.Json

/**
 * How results are written.
 *
 * `explicitNulls = true`, deliberately and against the usual instinct to shrink output: an
 * absent metric is the whole point of this schema, and a field that vanishes when it is null
 * is indistinguishable from a field the producing version did not have. The analysis tool
 * reads `null` as "this device or engine could not measure it" and refuses to fill it in.
 */
val benchmarkJson: Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = true
}

fun BenchmarkFile.toJson(): String = benchmarkJson.encodeToString(BenchmarkFile.serializer(), this)

fun parseBenchmarkFile(text: String): BenchmarkFile =
    benchmarkJson.decodeFromString(BenchmarkFile.serializer(), text)
