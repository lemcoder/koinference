package io.github.lemcoder.koinference.whisper.internal

/** A whisper binding that records instead of transcribing. */
internal class FakeWhisperBridge : WhisperBridge {

    val models = mutableListOf<FakeWhisperModel>()

    val model: FakeWhisperModel get() = models.last()

    /** What a transcript looks like, as a function of how many samples arrived. */
    var transcript: (FloatArray) -> String = { "transcript of ${it.size} samples" }

    override fun openModel(options: WhisperModelOptions): WhisperModel =
        FakeWhisperModel(options, transcript).also { models += it }
}
