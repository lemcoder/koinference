package io.github.lemcoder.koinference.llamacpp.internal

/** Nothing to refuse: macOS, iOS and Linux each link an archive built for the machine that runs it. */
internal actual fun llamaCppUnsupportedReason(): String? = null
