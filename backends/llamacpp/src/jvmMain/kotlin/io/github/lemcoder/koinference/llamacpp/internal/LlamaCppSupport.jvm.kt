package io.github.lemcoder.koinference.llamacpp.internal

/**
 * Nothing to refuse: the desktop JVM leg loads a library built for the machine it is running on,
 * and the CMake preset for a host build leaves ggml to pick its own baseline.
 */
internal actual fun llamaCppUnsupportedReason(): String? = null
