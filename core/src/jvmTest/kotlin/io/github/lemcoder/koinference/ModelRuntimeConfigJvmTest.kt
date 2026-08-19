package io.github.lemcoder.koinference

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelRuntimeConfigJvmTest {

    @Test
    fun `runtime settings default to cpu backend`() {
        assertEquals(Accelerator.CPU, RuntimeSettings().accelerator)
    }
}
