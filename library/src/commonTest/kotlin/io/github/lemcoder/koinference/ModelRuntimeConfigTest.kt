package io.github.lemcoder.koinference

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelRuntimeConfigTest {

    @Test
    fun `runtime settings default to cpu backend`() {
        assertEquals(InferenceBackend.CPU, RuntimeSettings().backend)
    }
}
