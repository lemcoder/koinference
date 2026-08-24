package io.github.lemcoder.koinference.benchmark

import io.github.lemcoder.koinference.runtime.ResponsePart

/** The text of a reply, for tests that assert on it. See the note in :core's test helper. */
internal fun List<ResponsePart>.text(): String =
    filterIsInstance<ResponsePart.Text>().joinToString("") { it.text }
