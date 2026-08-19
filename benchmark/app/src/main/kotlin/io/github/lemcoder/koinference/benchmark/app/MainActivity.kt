package io.github.lemcoder.koinference.benchmark.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Shows where the server is and how to drive it. It is not a chat UI on purpose.
 *
 * Anything this Activity did with a model would run in the *app* process, and the service runs
 * in its own so that the model's memory can be read without a UI mixed into it. So the Activity
 * starts the service and gets out of the way; the example of how to use the library is
 * [LoadedModel], which is three calls long.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = TextView(this).apply {
            movementMethod = ScrollingMovementMethod()
            setPadding(32, 32, 32, 32)
            textSize = 13f
        }
        setContentView(text)

        val address = localAddress() ?: "unknown"
        text.text = """
            koinference benchmark server

            This screen starts nothing by itself. Push a model and start the service:

              adb push model.gguf /sdcard/Download/koinference/
              adb shell am start-foreground-service \
                -n ${packageName}/.InferenceService \
                --es modelPath /sdcard/Download/koinference/model.gguf \
                --ei port ${InferenceService.DEFAULT_PORT}

            Then, from this network:

              curl http://$address:${InferenceService.DEFAULT_PORT}/v1/models

            The server binds every interface with no authentication. Anyone who can reach this
            device can drive the model. Pass --es bind 127.0.0.1 and use adb forward when that
            is not what you want.
        """.trimIndent()
    }

    /** First non-loopback IPv4 address, which is what a client on the same network dials. */
    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?.hostAddress
    }.getOrNull()
}
