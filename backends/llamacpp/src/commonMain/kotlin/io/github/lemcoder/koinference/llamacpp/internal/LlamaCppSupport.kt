package io.github.lemcoder.koinference.llamacpp.internal

/** Why this device cannot run llama.cpp, or null when it can. Only Android answers with anything. */
internal expect fun llamaCppUnsupportedReason(): String?
