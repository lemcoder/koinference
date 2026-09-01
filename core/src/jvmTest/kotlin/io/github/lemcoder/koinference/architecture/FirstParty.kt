package io.github.lemcoder.koinference.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration

/**
 * Kotlin this repository is answerable for.
 *
 * `scopeFromProject()` walks the whole checkout, which includes llama.cpp's own Android example
 * under `native/.cpm` — that is where `src/test` and `src/androidTest` appear from, and neither is
 * ours to have an opinion about. Downloaded and generated trees go the same way.
 */
internal fun firstParty(): List<KoFileDeclaration> = Konsist.scopeFromProject()
    .files
    .filterNot { file -> VENDORED.any { file.path.replace('\\', '/').contains(it) } }

/**
 * Every `actual` declaration in a file, as name to source set.
 *
 * Top-level functions, classes and properties, because all three are used as actuals here —
 * `platformCpuPlacement` is a function, `readFileBytes` is a function, and a backend could just as
 * easily need a class.
 */
internal fun KoFileDeclaration.declarationsWithActual(): List<Pair<String, String>> =
    named { it.hasActualModifier }
internal fun KoFileDeclaration.declarationsWithExpect(): List<Pair<String, String>> =
    named { it.hasExpectModifier }
internal fun KoFileDeclaration.named(
    predicate: (Modifiers) -> Boolean,
): List<Pair<String, String>> {
    val functions = functions().filter { predicate(Modifiers(it.hasActualModifier, it.hasExpectModifier)) }
        .map { it.name to sourceSetName }
    val classes = classes().filter { predicate(Modifiers(it.hasActualModifier, it.hasExpectModifier)) }
        .map { it.name to sourceSetName }
    val properties = properties().filter { predicate(Modifiers(it.hasActualModifier, it.hasExpectModifier)) }
        .map { it.name to sourceSetName }
    return functions + classes + properties
}
/**
 * Names of the top-level classes, interfaces, objects and enums a file declares.
 *
 * Top level only: nested types belong to their parent, and a companion object is part of its class,
 * so neither counts against the one-type-per-file rule.
 */
internal fun KoFileDeclaration.topLevelTypeNames(): List<String> =
    (classes() + interfaces() + objects()).filter { it.isTopLevel }.mapNotNull { it.name }
/** The file name, without its directories. */
internal fun KoFileDeclaration.basename(): String =
    path.replace('\\', '/').substringAfterLast('/')
/**
 * Paths holding code this repository did not write.
 *
 * Downloads, build output, and `local-bindings/` — where a sideloaded engine's *generated* bindings
 * are staged. UniFFI emits one 9,000-line file with 146 top-level types in it; judging that by the
 * rules this repository holds its own code to would say nothing about this repository.
 */
internal val VENDORED = listOf(
    "/.cpm/",
    "/build/",
    "/.prebuilt/",
    "/.gradle/",
    "/.codegraph/",
    "/local-bindings/",
)
