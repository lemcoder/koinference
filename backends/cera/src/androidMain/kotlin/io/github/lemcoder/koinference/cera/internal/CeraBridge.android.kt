package io.github.lemcoder.koinference.cera.internal

/** Both legs link the same UniFFI binding; only the packaged natives differ. */
internal actual fun platformBridge(): CeraBridge = UniffiBridge
