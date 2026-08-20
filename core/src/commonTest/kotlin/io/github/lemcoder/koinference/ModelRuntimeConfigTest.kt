package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.runtime.Accelerator
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelRuntimeConfigTest {

    @Test
    fun `runtime settings default to cpu backend`() {
        assertEquals(Accelerator.CPU, RuntimeSettings().accelerator)
    }
}
