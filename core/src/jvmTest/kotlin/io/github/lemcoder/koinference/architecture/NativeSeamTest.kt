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
     * Files allowed to name native symbols.
     *
     * The bindings, and the tests whose entire purpose is to compare the two sides of the boundary:
     * `SessionDefaultsTest` checks the hand-written Kotlin sampler defaults against
     * `koilm_default_session_params()`, and `BackendIdTest` checks the hand-written backend ids
     * against the generated enum. Those exist because the values are duplicated on purpose — only
     * the cinterop leg gets generated constants — and they are what stops the copies drifting.
     */
    private val bindings = setOf(
        "FacadeBridge", "JniBridge",
        "LlamaCppBridgeJvmSmokeTest", "SessionDefaultsTest", "BackendIdTest",
    )

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
            .filterNot { it.name in bindings }
            .map { "${it.name} (${it.sourceSetName})" }

        assertTrue(
            leaks.isEmpty(),
            "native symbols imported outside a binding file: $leaks. C reaches the engine; it does " +
                "not decide things — rule 2 in CLAUDE.md.",
        )
    }
}
