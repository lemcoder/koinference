package io.github.lemcoder.koinference.executorch

import io.github.lemcoder.koinference.runtime.ResponsePart

/** The text of a reply, for tests that assert on it. `:core` offers no such shortcut on purpose. */
internal fun List<ResponsePart>.text(): String =
    filterIsInstance<ResponsePart.Text>().joinToString("") { it.text }
