package io.github.lemcoder.koinference.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration

/** The two modifiers this file cares about, so one predicate can serve all three declaration kinds. */
internal data class Modifiers(val hasActualModifier: Boolean, val hasExpectModifier: Boolean)
