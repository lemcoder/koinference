package io.github.lemcoder.koinference.benchmark.platform

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

actual fun platformProbe(): PlatformProbe = AndroidProbe
