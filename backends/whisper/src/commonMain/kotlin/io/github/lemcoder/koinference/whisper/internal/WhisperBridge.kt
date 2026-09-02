package io.github.lemcoder.koinference.whisper.internal

/** The whisper.cpp binding, as something a test can replace. */
internal interface WhisperBridge {

    fun openModel(options: WhisperModelOptions): WhisperModel
}

/** The binding this platform links: generated JNI bridges on ART, cinterop everywhere else. */
internal expect fun platformBridge(): WhisperBridge
