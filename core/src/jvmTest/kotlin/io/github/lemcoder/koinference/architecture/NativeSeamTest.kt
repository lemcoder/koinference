package io.github.lemcoder.koinference.architecture

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The checkable half of rule 2 in CLAUDE.md: C reaches the engine, it does not decide things.
 *
 * Most of that rule is a judgement call and Konsist cannot see C at all. What it *can* enforce is
 * the Kotlin side of the boundary: native symbols belong in the binding files and nowhere else. Once
 * a `koi_*` call appears in a runtime or a loader, logic and marshalling have started to mix, and
 * the next step is a rule living in C because that was where the call already was.
 *
 * This is the same containment that lets `CpuPlacementPolicy` be pure Kotlin with eleven tests
 * behind it, instead of a C function whose decision could only be inferred from throughput.
 */
class NativeSeamTest {

    /**
     * Two tests are allowed to name native symbols because comparing the two sides is their whole
     * purpose: `SessionDefaultsTest` checks the hand-written Kotlin sampler defaults against
     * `koilm_default_session_params()`, and `BackendIdTest` checks the hand-written backend ids
     * against the generated enum. Both exist because those values are duplicated on purpose — only
     * the cinterop leg gets generated constants — and they are what stops the copies drifting.
     */
    private val boundaryTests = setOf(
        "LlamaCppBridgeJvmSmokeTest.kt", "SessionDefaultsTest.kt", "BackendIdTest.kt",
    )

    /**
     * A binding file, by name rather than by an exhaustive list.
     *
     * `Jni*`/`Facade*` are the per-leg implementations and `*Bridge.<platform>.kt` are the actuals
     * that hand them out. A list of exact names had to be edited every time a binding was split
     * into another file, which is churn that says nothing — the invariant is that native symbols
     * stay inside the bindings, not that there are exactly eleven of them.
     */
    private fun isBinding(name: String) =
        name.startsWith("Jni") || name.startsWith("Facade") || Regex("""Bridge\.\w+\.kt$""").containsMatchIn(name)

    @Test
    fun `native symbols are named only by the binding files`() {
        val leaks = firstParty()
            .filter { file ->
                file.imports.any { import ->
                    import.name.contains(".koi_") ||
                        import.name.contains(".koilm_") ||
                        import.name.contains(".kniBridge") ||
                        import.name.contains(".kniCString")
                }
            }
            .filterNot { isBinding(it.basename()) || it.basename() in boundaryTests }
            .map { "${it.name} (${it.sourceSetName})" }

        assertTrue(
            leaks.isEmpty(),
            "native symbols imported outside a binding file: $leaks. C reaches the engine; it does " +
                "not decide things — rule 2 in CLAUDE.md.",
        )
    }
}
