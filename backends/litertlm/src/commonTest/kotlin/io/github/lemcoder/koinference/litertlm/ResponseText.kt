package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.runtime.ResponsePart

/**
 * The text of a reply, for tests that assert on it.
 *
 * A test helper, not part of the library: `:core` offers no text shortcut, because a caller that
 * filters a reply down to text should be able to see it is dropping whatever else was there.
 */
internal fun List<ResponsePart>.text(): String =
    filterIsInstance<ResponsePart.Text>().joinToString("") { it.text }

/** The text of a streamed reply, part by part. */
internal fun List<ResponsePart>.textParts(): List<String> =
    filterIsInstance<ResponsePart.Text>().map { it.text }
