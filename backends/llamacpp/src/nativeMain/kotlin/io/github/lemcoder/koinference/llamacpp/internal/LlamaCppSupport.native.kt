package io.github.lemcoder.koinference.llamacpp.internal

/**
 * Nothing to refuse. macOS, iOS and Linux each link an archive built for that target, and none of
 * them is cross-compiled down to a baseline the way the Android AAR is — the one shipped binary
 * that has to survive hardware it was not built on.
 *
 * One actual for the three rather than three identical ones: they do not differ here. Where they
 * genuinely do — CPU placement — they get one each.
 */
internal actual fun llamaCppUnsupportedReason(): String? = null
