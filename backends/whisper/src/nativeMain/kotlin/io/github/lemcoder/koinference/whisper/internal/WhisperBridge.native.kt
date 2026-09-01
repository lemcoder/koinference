package io.github.lemcoder.koinference.whisper.internal

/** Apple and Linux reach the same facade through cinterop rather than JNI. */
internal actual fun platformBridge(): WhisperBridge = FacadeBridge
