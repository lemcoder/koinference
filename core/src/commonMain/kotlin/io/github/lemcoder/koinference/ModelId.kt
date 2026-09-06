package io.github.lemcoder.koinference

import kotlin.jvm.JvmInline

/**
 * An opaque handle to a model loaded into memory by [Koinference.loadModel].
 *
 * A value class over a `String`: type-safe (a random string is not a handle), zero-cost, and it
 * prints readably in a log — unlike an `Int` handle, which invites "3 vs 4" confusion and reads the
 * same before and after the model behind it was unloaded.
 */
@JvmInline
value class ModelId(val value: String)
