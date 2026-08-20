package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.backend.SamplingKnob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class FakeBackend(
    override val id: String,
    private val extensions: List<String>,
    override val honours: Set<SamplingKnob> = emptySet(),
) : Backend {
    override fun handles(modelPath: String) = extensions.any { modelPath.endsWith(it) }
    override fun loader(config: ModelConfig): ModelLoader = error("not needed")
}
