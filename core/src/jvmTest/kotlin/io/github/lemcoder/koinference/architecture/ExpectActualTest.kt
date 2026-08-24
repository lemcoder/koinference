package io.github.lemcoder.koinference.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rule 3 in CLAUDE.md, enforced: a platform difference is `expect`/`actual` per platform.
 *
 * The rule exists because sharing hid a bug rather than because it looked untidy. CPU placement had
 * one `nativeMain` implementation saying "no pinning, cores - 2" — right for macOS, untested on iOS,
 * and simply wrong for Linux, where `sched_setaffinity` works and `/proc` is there to read. Nothing
 * failed. Linux just silently got the Darwin answer.
 *
 * So the interesting assertion is not "an actual exists" — that was already true — but that the
 * targets which can differ each have their own, and that a shared parent has not absorbed them
 * again.
 */
class ExpectActualTest {

    /**
     * Source sets that may hold an `actual`.
     *
     * `macosArm64Main` is per *target* rather than per family, which is the rule taken one step
     * further: `:benchmark:core` builds for android and macosArm64 only, so its platform probe has
     * nowhere more specific to live. `nativeMain` is here because some things genuinely are
     * identical across native targets — the cinterop bridges are — but placement is not, which the
     * last test pins down.
     */
    private val platformSourceSets = setOf(
        "jvmMain", "androidMain", "nativeMain",
        "macosMain", "iosMain", "linuxMain", "macosArm64Main",
    )

    @Test
    fun `every actual lives in a platform source set`() {
        val misplaced = firstParty()
            .flatMap { it.declarationsWithActual() }
            .filterNot { it.second in platformSourceSets }
            .map { "${it.first} in ${it.second}" }
            .distinct()

        assertTrue(misplaced.isEmpty(), "actual declarations outside a platform source set: $misplaced")
    }

    @Test
    fun `every expect is answered somewhere`() {
        val expects = firstParty().flatMap { it.declarationsWithExpect() }.map { it.first }.toSet()
        val actuals = firstParty().flatMap { it.declarationsWithActual() }.map { it.first }.toSet()

        // The coarse half of the rule: Konsist cannot tell which targets a module declares, so an
        // unanswered expect is all this can catch here. The fine half is the test below.
        assertTrue((expects - actuals).isEmpty(), "expect with no actual anywhere: ${expects - actuals}")
    }

    @Test
    fun `cpu placement is answered per platform, not per family`() {
        // The concrete regression the rule was written for. Five legs, five answers: Android pins its
        // big cluster, macOS cannot pin and wants cores - 2, iOS cannot pin either but its 2/4 core
        // split is nothing like an M4's 4/6, Linux *can* pin, and the JVM cannot know at compile time
        // which OS it is running on.
        //
        // nativeMain deliberately absent from the expected set: that is where the Linux bug lived.
        val legs = firstParty()
            .flatMap { it.declarationsWithActual() }
            .filter { it.first == "platformCpuPlacement" }
            .map { it.second }
            .toSet()

        assertEquals(
            setOf("jvmMain", "androidMain", "macosMain", "iosMain", "linuxMain"),
            legs,
            "platformCpuPlacement must be answered per platform — rule 3 in CLAUDE.md. A shared " +
                "nativeMain implementation gave Linux the Darwin answer and nothing failed.",
        )
    }
}
