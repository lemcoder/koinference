package io.github.lemcoder.koinference.whisper.internal

/** ART reaches the facade through generated JNI bridges, the same way llama.cpp does. */
internal actual fun platformBridge(): WhisperBridge = JniBridge
