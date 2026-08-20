package io.github.lemcoder.koinference.architecture

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A file holding `actual` declarations is named after the `expect` it answers, with its platform as
 * a suffix: `CpuPlacement.kt` in commonMain is answered by `CpuPlacement.android.kt`,
 * `CpuPlacement.macos.kt` and so on.
 *
 * The convention this repository used before named platform files after the *binding* instead —
 * `JniBridge.kt`, `FacadeBridge.kt` — to dodge a `Duplicate JVM class name` error when a commonMain
 * file with real declarations met a same-named androidMain file. The dotted suffix sidesteps that on
 * its own, because `CpuPlacement.android.kt` and `CpuPlacement.kt` produce different JVM facade
 * classes, so the workaround is no longer needed and the naming can say something useful instead:
 * which `expect` a file answers, and for which platform.
 *
 * Worth having enforced because the failure mode is silent. A stray `Foo.kt` in `iosMain` still
 * compiles and still provides its actual; nothing tells you the file no longer says what it is for,
 * and the next person looking for the iOS answer to `CpuPlacement` does not find it by name.
 */
class ActualFileNamingTest {

    /**
     * The suffix each source set must use.
     *
     * `macosArm64Main` is per target rather than per family — `:benchmark:core` builds for android
     * and macosArm64 only — and its suffix follows the source set, as everything here does.
     */
    private val suffixes = mapOf(
        "jvmMain" to "jvm",
        "androidMain" to "android",
        "nativeMain" to "native",
        "macosMain" to "macos",
        "iosMain" to "ios",
        "linuxMain" to "linux",
        "macosArm64Main" to "macosArm64",
    )

    @Test
    fun `files with actuals are named after their expect, with a platform suffix`() {
        val offenders = firstParty()
            .filter { it.declarationsWithActual().isNotEmpty() }
            .mapNotNull { file ->
                val expected = suffixes[file.sourceSetName] ?: return@mapNotNull null
                val name = file.basename()
                if (name.endsWith(".$expected.kt")) null else "$name should end in .$expected.kt"
            }

        assertTrue(
            offenders.isEmpty(),
            "actual files must be named <Expect>.<platform>.kt: $offenders",
        )
    }

    @Test
    fun `every actual file has a commonMain counterpart of the same name`() {
        val common = firstParty()
            .filter { it.sourceSetName == "commonMain" }
            .map { it.basename().removeSuffix(".kt") to it.moduleName }
            .toSet()

        val orphans = firstParty()
            .filter { it.declarationsWithActual().isNotEmpty() }
            .mapNotNull { file ->
                val suffix = suffixes[file.sourceSetName] ?: return@mapNotNull null
                val name = file.basename()
                // Strip ".<platform>.kt" to get back to the expect's file name.
                // The second removeSuffix catches a file that failed the naming test above, so its
                // message names a plausible counterpart instead of "Foo.kt.kt".
                val base = name.removeSuffix(".$suffix.kt").removeSuffix(".kt")
                if (base to file.moduleName in common) null else "$name has no $base.kt in commonMain"
            }

        assertTrue(
            orphans.isEmpty(),
            "an actual file must answer an expect declared in a commonMain file of the same " +
                "name: $orphans",
        )
    }
}
