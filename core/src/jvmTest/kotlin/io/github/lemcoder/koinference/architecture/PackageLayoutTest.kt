package io.github.lemcoder.koinference.architecture

import com.lemonappdev.konsist.api.Konsist
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A file's package matches its directory, and no package is a pile.
 *
 * The first half is the usual Kotlin convention, and Konsist checks it directly. It matters more
 * than usual here because files were moved into sub-packages mechanically, and a package line that
 * disagrees with the path still compiles — nothing would have told us.
 *
 * The second half is why the sub-packages exist. `:benchmark:core` had thirty-four files in one
 * package and the app had sixteen, so finding a type meant scrolling. The cap is deliberately loose:
 * it is there to catch a package quietly becoming the next dumping ground, not to force a split at
 * a particular number.
 */
class PackageLayoutTest {

    @Test
    fun `package declarations match the directory they sit in`() {
        val mismatched = Konsist.scopeFromProject()
            .packages
            .filterNot { pkg -> VENDORED.any { pkg.path.replace('\\', '/').contains(it) } }
            .filterNot { it.hasMatchingPath }
            .map { "${it.name} at ${it.path.substringAfterLast('/')}" }

        assertTrue(mismatched.isEmpty(), "package does not match its directory: $mismatched")
    }

    @Test
    fun `no package holds more than twenty files`() {
        // Grouped by package *and* source set, because that pair is what a directory actually is.
        // Counting a package across source sets makes llamacpp.internal look like 39 files when no
        // single directory holds more than nine.
        val crowded = firstParty()
            .groupBy { (it.packagee?.name ?: "") to it.sourceSetName }
            .filterValues { it.size > 20 }
            .map { (key, files) -> "${key.first} (${key.second}) has ${files.size} files" }

        assertTrue(
            crowded.isEmpty(),
            "these packages want splitting into sub-packages: $crowded",
        )
    }
}
