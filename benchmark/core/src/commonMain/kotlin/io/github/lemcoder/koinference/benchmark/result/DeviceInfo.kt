package io.github.lemcoder.koinference.benchmark.result

import kotlinx.serialization.Serializable

/**
 * The device a run happened on.
 *
 * The marketing model name is not enough to identify hardware — two "Galaxy S23" phones can
 * carry different SoCs — so the SoC, ABI and core layout are recorded where the platform
 * exposes them.
 */
@Serializable
data class DeviceInfo(
    val manufacturer: String? = null,
    val model: String? = null,
    val device: String? = null,
    val androidVersion: String? = null,
    val sdk: Int? = null,
    val abi: String? = null,
    val supportedAbis: List<String> = emptyList(),
    val socManufacturer: String? = null,
    val socModel: String? = null,
    val hardware: String? = null,
    val cpuCores: Int? = null,
    val cpuMaxFrequenciesKhz: List<Long> = emptyList(),
    val ramMb: Long? = null,
    /** Set from the FTL matrix when the harness is told which entry it is running as. */
    val ftlModelId: String? = null,
    val ftlVersion: String? = null,
    /** Set when the harness knows it is not on real hardware. */
    val isEmulator: Boolean? = null,
    /** Host platform when this is not an Android run at all, e.g. "macosArm64". */
    val hostPlatform: String? = null,
)
