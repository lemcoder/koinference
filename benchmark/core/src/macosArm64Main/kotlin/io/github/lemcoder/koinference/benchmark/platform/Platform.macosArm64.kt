@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.benchmark.platform

import kotlin.time.TimeSource

/**
 * The host probe: timing, and almost nothing else.
 *
 * This target exists so the harness itself can be exercised where both engines really run. It
 * is not a device, and it does not pretend to be one — memory, thermal and battery all return
 * null, which is the same thing an Android device reports when a reading is genuinely
 * unavailable. If these returned plausible host numbers instead, a macOS run would produce a
 * file that looked like a phone measurement.
 */
actual fun platformProbe(): PlatformProbe = MacosProbe
