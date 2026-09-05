package io.github.lemcoder.koinference.whisper.internal

// An opaque `typedef struct KoiwModel KoiwModel;` has no definition for cinterop to translate, so
// it lands under cnames.structs rather than beside the functions in the interop's own package.
import cnames.structs.KoiwModel
import koinference_whisper.koiw_last_error
import koinference_whisper.koiw_model_load
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString

/** A transcript segment cannot outgrow this; whisper's are a sentence or so. */
internal const val SEGMENT_BYTES = 4096

@OptIn(ExperimentalForeignApi::class)
internal object FacadeBridge : WhisperBridge {

    override fun openModel(options: WhisperModelOptions): WhisperModel {
        val handle = koiw_model_load(options.modelPath, if (options.useGpu) 1 else 0)
            ?: error("whisper could not load ${options.modelPath}: ${lastError()}")
        return FacadeModel(handle)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun lastError(): String = memScoped {
    val buffer = allocArray<ByteVar>(SEGMENT_BYTES)
    val size = koiw_last_error(buffer, SEGMENT_BYTES)
    if (size <= 0) "no detail" else buffer.toKString()
}
