package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.runtime.Accelerator
import io.github.lemcoder.koinference.runtime.GenerationParameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelConfigTest {

    @Test
    fun defaultsLeaveEveryEngineChoiceAlone() {
        // 0 and null mean "the engine's own", so an unset field never imposes a value that
        // happens to be this library's opinion.
        val config = ModelConfig()

        assertEquals(0, config.contextTokens)
        assertEquals(0, config.maxOutputTokens)
        assertEquals(0, config.threads)
        assertNull(config.systemPrompt)
        assertNull(config.cacheDir)
        assertEquals(Accelerator.CPU, config.settings.accelerator)
        assertEquals(GenerationParameters(), config.parameters)
    }
}
