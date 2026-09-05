package io.github.lemcoder.koinference.cera

import io.github.lemcoder.koinference.runtime.ResponsePart

/** The text of a reply, for tests that assert on it. `:core` offers no such shortcut on purpose. */
internal fun List<ResponsePart>.text(): String =
    filterIsInstance<ResponsePart.Text>().joinToString("") { it.text }

/** The text of a streamed reply, part by part. */
internal fun List<ResponsePart>.textParts(): List<String> =
    filterIsInstance<ResponsePart.Text>().map { it.text }
