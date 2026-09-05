package io.github.lemcoder.koinference.runtime
import io.github.lemcoder.koinference.runtime.generation.Accelerator

data class RuntimeSettings(
    val accelerator: Accelerator = Accelerator.CPU,
)
