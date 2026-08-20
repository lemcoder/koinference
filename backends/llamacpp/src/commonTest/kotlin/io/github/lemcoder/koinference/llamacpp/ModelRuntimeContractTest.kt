package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.llamacpp.internal.CpuPlacementPolicy
import io.github.lemcoder.koinference.llamacpp.internal.FakeLlamaCppBridge
import io.github.lemcoder.koinference.llamacpp.internal.MutableMachine
import io.github.lemcoder.koinference.runtime.Accelerator
import io.github.lemcoder.koinference.runtime.GenerationParameters
import io.github.lemcoder.koinference.runtime.ModelRuntime
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The point of hoisting the parameter and settings members onto [ModelRuntime]: a caller that
 * resolved a backend from the registry holds whatever `load` returned and can retune it without
 * knowing which engine answered.
 *
 * Deliberately typed as [ModelRuntime] rather than as [LlamaCppTextRuntime] — that is the whole
 * assertion. Before the hoist this needed a cast to a backend-specific interface, which put the
 * caller back in the business of knowing which engine it had.
 */
class ModelRuntimeContractTest {

    private val bridge = FakeLlamaCppBridge()

    private suspend fun runtime(): ModelRuntime = LlamaCppModelLoader(
        bridge = bridge,
        config = ModelConfig(),
        placementPolicy = CpuPlacementPolicy(MutableMachine()),
    ).load("/models/a.gguf")

    @Test
    fun aRuntimeReportsWhatItWasLoadedWith() = runTest {
        val runtime = runtime()

        assertEquals(GenerationParameters(), runtime.generationParameters)
        assertEquals(Accelerator.CPU, runtime.runtimeSettings.accelerator)
    }

    @Test
    fun samplingCanBeChangedThroughTheCommonType() = runTest {
        val runtime = runtime()

        runtime.updateGenerationParameters(GenerationParameters(topK = 3))

        assertEquals(3, runtime.generationParameters.topK)
    }

    @Test
    fun theDeviceCanBeChangedThroughTheCommonType() = runTest {
        val runtime = runtime()

        runtime.updateRuntimeSettings(RuntimeSettings(Accelerator.GPU))

        // Reported from the reloaded model rather than from a field, so it cannot claim a device
        // the weights are not on.
        assertEquals(Accelerator.GPU, runtime.runtimeSettings.accelerator)
        assertEquals(Accelerator.GPU, bridge.model.options.accelerator)
    }
}
