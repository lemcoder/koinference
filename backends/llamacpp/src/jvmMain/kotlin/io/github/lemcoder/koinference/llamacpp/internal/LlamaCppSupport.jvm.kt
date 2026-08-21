package io.github.lemcoder.koinference.llamacpp.internal

/** Nothing to refuse: the desktop JVM leg loads a library built for the machine that runs it. */
internal actual fun llamaCppUnsupportedReason(): String? = null
