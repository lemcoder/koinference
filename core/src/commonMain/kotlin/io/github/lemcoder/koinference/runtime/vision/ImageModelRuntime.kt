package io.github.lemcoder.koinference.runtime.vision

import io.github.lemcoder.koinference.runtime.ModelRuntime

/**
 * Everything a loaded image model can do, as one type.
 *
 * The sibling of `TextModelRuntime`, and the reason `ModelLoader.load` cannot promise text: with two
 * modalities the loader no longer knows which of these it is handing back. See `Koinference.loadText`
 * and `loadVision`.
 */
interface ImageModelRuntime : ModelRuntime, ImageRuntime
