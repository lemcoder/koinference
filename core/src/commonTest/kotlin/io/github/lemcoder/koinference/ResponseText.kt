package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.runtime.ResponsePart

/**
 * The text of a reply, for tests that assert on it.
 *
 * A test helper and deliberately not part of `:core`: the library offers no text shortcut, because a
 * caller that filters a reply down to text should be able to see that it is dropping whatever else
 * was in it.
 */
internal fun List<ResponsePart>.text(): String =
    filterIsInstance<ResponsePart.Text>().joinToString("") { it.text }
