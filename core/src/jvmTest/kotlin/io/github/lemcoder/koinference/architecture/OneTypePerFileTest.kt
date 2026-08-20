package io.github.lemcoder.koinference.architecture

import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.GenerationConstraint
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * One top-level class, interface, object or enum per file, named after it.
 *
 * Before this, `BenchmarkResult.kt` held thirteen types and `OpenAiApi.kt` twelve, so finding
 * `ThermalSample` or `ChatChoice` meant knowing which grab-bag it had been put in. Twenty-three
 * files were like that; they are about ninety-five now.
 *
 * Nested declarations are untouched — `PromptPart.Text` and `GenerationConstraint.JsonSchema` belong
 * inside their sealed parent, and a companion object is part of its class. Only the top level is
 * one-per-file.
 *
 * Top-level functions and properties may sit alongside, which is what keeps `CpuPlacement.kt` able
 * to hold both the `CpuPlacement` type and the `expect fun platformCpuPlacement()` that the
 * `<Expect>.<platform>.kt` naming rule requires to live there.
 */
class OneTypePerFileTest {

    @Test
    fun `a file declares at most one top-level type`() {
        val offenders = firstParty()
            .map { file -> file.basename() to file.topLevelTypeNames() }
            .filter { (_, types) -> types.size > 1 }
            .map { (name, types) -> "$name declares ${types.size}: $types" }

        assertTrue(
            offenders.isEmpty(),
            "one top-level type per file: $offenders",
        )
    }

    @Test
    fun `a file with a top-level type is named after it`() {
        val misnamed = firstParty()
            .mapNotNull { file ->
                val type = file.topLevelTypeNames().singleOrNull() ?: return@mapNotNull null
                // The platform suffix is the other naming rule's business; strip it before
                // comparing, so CpuPlacement.android.kt is judged as CpuPlacement.
                val base = file.basename().removeSuffix(".kt").substringBefore('.')
                if (base == type) null else "${file.basename()} declares $type"
            }

        assertTrue(
            misnamed.isEmpty(),
            "a file holding one type takes that type's name: $misnamed",
        )
    }
}
