package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.runtime.ResponsePart

/**
 * The text of a reply, for tests that assert on it.
 *
 * A copy of the commonTest helper, because androidDeviceTest is its own compilation and does not
 * see it. Duplication rather than a shared test source set, per the rules at the top of CLAUDE.md.
 */
internal fun List<ResponsePart>.text(): String =
    filterIsInstance<ResponsePart.Text>().joinToString("") { it.text }

/** The text of a streamed reply, part by part. */
internal fun List<ResponsePart>.textParts(): List<String> =
    filterIsInstance<ResponsePart.Text>().map { it.text }
