package io.github.lemcoder.koinference.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rule 1 in CLAUDE.md, enforced: source sets are not for sharing code.
 *
 * The banned thing is an intermediate added to deduplicate — `jvmSharedMain`, or an `appleMain` that
 * exists only so two legs can avoid a duplicated file. Both were tried in this repo and both were
 * wrong: the JNI bridges cannot be shared at all, because the generated `…jni` functions land in
 * each target's own source set and a shared parent cannot see them; and an `appleMain` holding CPU
 * placement hid that macOS and iOS want different answers.
 *
 * An allowlist rather than a pattern, so adding a source set is a deliberate act with a reason
 * recorded here, not something that happens because a file was dropped in a new directory.
 */
class SourceSetLayoutTest {

    /**
     * Every source set this repository is allowed to have, and why it exists.
     *
     * `commonMain`/`commonTest`      — shared code and its tests.
     * `jvmMain`/`jvmTest`            — the JVM leg of the JNI bridges.
     * `androidMain`                  — the ART leg; duplicates jvmMain rather than sharing it.
     * `androidDeviceTest`            — instrumented tests; the only place a packaged .so is loaded.
     * `nativeMain`/`nativeTest`      — cinterop code identical on every native target.
     * `macosMain`/`iosMain`/`linuxMain` — where native targets genuinely differ. See rule 3.
     * `appleTest`/`macosArm64Test`   — native tests that need a real facade archive.
     * `nativeInterop`                — .def files, not Kotlin.
     * `macosArm64Main`               — the harness's host platform probe, one target only.
     * `main`                         — benchmark/app, a plain Android module in its own build.
     */
    private val allowed = setOf(
        "commonMain", "commonTest",
        "jvmMain", "jvmTest",
        "androidMain", "androidDeviceTest",
        "nativeMain", "nativeTest",
        "macosMain", "iosMain", "linuxMain",
        "appleTest", "macosArm64Test",
        "nativeInterop",
        "macosArm64Main",
        "main",
    )

    @Test
    fun `every kotlin file lives in an allowed source set`() {
        // sourceSetName comes from Konsist rather than from parsing the path. An earlier version
        // split on "src" and this checkout lives under ~/src, so every source set read as the
        // repository's own name.
        val offenders = firstParty()
            .map { it.sourceSetName }
            .distinct()
            .filterNot { it in allowed }

        assertTrue(
            offenders.isEmpty(),
            "Unexpected source sets: $offenders. Source sets are not for sharing code — see " +
                "rule 1 in CLAUDE.md. If a platform genuinely differs, add it to `allowed` here " +
                "with a line saying why.",
        )
    }

    @Test
    fun `the llama_cpp bridge has one actual per leg and no shared parent`() {
        // Not a style preference: an intermediate source set holding the hand-written actual could
        // not see the generated kniBridgeN functions, which are produced per compilation into each
        // target's own source set.
        val bridges = firstParty()
            .filter { it.basename().startsWith("LlamaCppBridge.") }
            // commonMain's LlamaCppBridge.kt matches that prefix too; the actuals are what matter.
            .filter { it.declarationsWithActual().isNotEmpty() }
            .map { it.sourceSetName }
            .toSet()

        assertEquals(
            setOf("jvmMain", "androidMain", "nativeMain"),
            bridges,
            "LlamaCppBridge needs one actual file per leg. The two ART legs duplicate rather than " +
                "share, because the generated kniBridgeN functions land per target and a shared " +
                "parent cannot see them.",
        )
    }

}
