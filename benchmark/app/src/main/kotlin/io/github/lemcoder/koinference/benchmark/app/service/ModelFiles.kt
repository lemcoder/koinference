package io.github.lemcoder.koinference.benchmark.app.service

import android.content.Context
import io.github.lemcoder.koinference.backend.Backend
import java.io.File

/**
 * Where a pushed model can be found on a test device.
 *
 * Not a file picker: models arrive over `adb push`, and the device that runs this has no user
 * sitting in front of it choosing files. The list is short and fixed so a run is reproducible from
 * a shell script.
 *
 * `/sdcard/Android/data/<pkg>/files` is wiped on reinstall, which AGP does on every instrumented
 * run — so it is last, and the two paths that survive a reinstall come first.
 */
object ModelFiles {

    fun searchPaths(context: Context): List<File> = listOfNotNull(
        File("/sdcard/Download/koinference"),
        File("/data/local/tmp/koinference"),
        context.getExternalFilesDir(null),
    )

    /** Every model on this device that [backend] says it can read, deduplicated by file name. */
    fun forBackend(context: Context, backend: Backend): List<File> =
        searchPaths(context)
            .flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
            .filter { it.isFile && backend.handles(it.absolutePath) }
            .distinctBy { it.name }
            .sortedBy { it.name }
}
