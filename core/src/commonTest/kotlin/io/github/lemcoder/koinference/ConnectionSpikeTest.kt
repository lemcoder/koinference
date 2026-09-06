package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.runtime.KoinferenceException
import io.github.lemcoder.koinference.runtime.ResponsePart
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Model/Connection seam spike: loadModel -> openConnection -> generate(callback) -> close,
 * with no Flow on the public surface and no markClosed in the lifecycle.
 */
class ConnectionSpikeTest {

    private fun koi() = Koinference(FakeOmniBackend())

    @Test
    fun generateStreamsPartsThroughTheCallback() = runTest {
        val koi = koi()
        val id = koi.loadModel("model.omni")
        val connection = koi.openConnection(id)

        val parts = mutableListOf<ResponsePart>()
        connection.generate("hi") { parts += it }   // callback, not a Flow

        assertEquals(4, parts.size)
        assertEquals("Hello", (parts[0] as ResponsePart.Text).text)
        assertTrue(parts[1] is ResponsePart.Audio)
        assertEquals(" there", (parts[2] as ResponsePart.Text).text)
        connection.close()
    }

    @Test
    fun callingAfterCloseThrowsConnectionClosed() = runTest {
        val koi = koi()
        val connection = koi.openConnection(koi.loadModel("m.omni"))
        connection.close()
        assertFailsWith<KoinferenceException.ConnectionClosed> {
            connection.generate("hi") {}
        }
    }

    @Test
    fun openingOverAnUnknownModelThrows() = runTest {
        assertFailsWith<KoinferenceException.UnknownModel> {
            koi().openConnection(ModelId("never-loaded"))
        }
    }

    @Test
    fun unloadRefusesWhileAConnectionIsOpen() = runTest {
        val koi = koi()
        val id = koi.loadModel("m.omni")
        koi.openConnection(id)
        assertFailsWith<KoinferenceException.LoadFailed> { koi.unloadModel(id) }
    }

    @Test
    fun forcedUnloadFiresOnDeathAndKillsTheConnection() = runTest {
        val koi = koi()
        val id = koi.loadModel("m.omni")
        var death: KoinferenceException? = null
        val connection = koi.openConnection(id) { death = it }

        koi.unloadModel(id, force = true)

        assertIs<KoinferenceException.ModelUnloaded>(death)          // out-of-band channel fired
        assertFailsWith<KoinferenceException.ConnectionClosed> {     // and the connection is dead
            connection.generate("hi") {}
        }
    }
}
