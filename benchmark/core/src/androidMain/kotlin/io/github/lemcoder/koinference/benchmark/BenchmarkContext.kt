package io.github.lemcoder.koinference.benchmark

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.io.File

/**
 * The application context, set once by the benchmark runner before anything is measured.
 *
 * A global because the probe is reached from common code that has no Android types in its
 * signatures; the alternative is threading a Context through the whole harness so that one
 * platform can use it.
 */
object BenchmarkContext {
    @Volatile
    var applicationContext: Context? = null
}
