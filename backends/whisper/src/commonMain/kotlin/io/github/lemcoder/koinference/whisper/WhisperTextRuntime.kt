package io.github.lemcoder.koinference.whisper

import io.github.lemcoder.koinference.runtime.GeneratingRuntime

/**
 * What a caller holding a whisper model can do with it: hand it audio, get text.
 *
 * `GeneratingRuntime` and not a transcription interface of its own. A prompt has carried
 * `PromptPart.AudioFile` since before any engine could read one, and a reply has been a list of
 * `ResponsePart` since a model that interleaves speech with its transcript broke the older design.
 * Speech to text is that shape already — audio in, text out — so this backend needed nothing added
 * to `:core`, which is the strongest evidence so far that the parts-based seam was right.
 *
 * No `TokenCounting`: whisper's tokens are its own, and the facade exposes no tokenizer.
 */
interface WhisperTextRuntime : GeneratingRuntime
