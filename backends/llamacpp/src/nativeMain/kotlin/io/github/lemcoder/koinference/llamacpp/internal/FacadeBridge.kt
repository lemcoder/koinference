@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.llamacpp.internal

import cnames.structs.KoiModel
import cnames.structs.KoiSession
import io.github.lemcoder.koinference.runtime.Accelerator
import koinference.KoiSessionParams
import koinference.koi_backend_init
import koinference.koi_generate
import koinference.koi_generate_begin
import koinference.koi_generate_end
import koinference.koi_generate_next
import koinference.koi_json_schema_to_grammar
import koinference.koi_model_free
import koinference.koi_model_load
import koinference.koi_session_create
import koinference.koi_session_cpu_mask
import koinference.koi_session_free
import koinference.koi_session_set_cpu_mask
import koinference.koi_token_count
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

internal object FacadeBridge : LlamaCppBridge {

    // koi_backend_init has to run before the first model load, and llama_backend_free tears down
    // state every live model and session still points at — with no ownership information here to
    // reference-count, since a second loader may hold a model this one knows nothing about. So it
    // is initialised once and never freed: a fixed set of globals, released by the process
    // exiting. `lazy` is synchronised by default, and two coroutines loading models on different
    // threads must not both call init.
    private val backendInit: Unit by lazy { koi_backend_init() }

    override fun openModel(options: ModelOptions): LlamaCppModel {
        backendInit
        val handle = koi_model_load(
            options.modelPath,
            when (options.accelerator) {
                Accelerator.CPU -> 0
                Accelerator.GPU -> ALL_GPU_LAYERS
            },
        )
        checkNotNull(handle) { "llama.cpp could not load ${options.modelPath}" }
        return FacadeModel(handle)
    }

    override fun jsonSchemaToGrammar(schema: String): String = memScoped {
        val buffer = allocArray<ByteVar>(LARGE_BUFFER_BYTES)
        val written = koi_json_schema_to_grammar(schema, buffer, LARGE_BUFFER_BYTES)
        require(written > 0) { "Not a convertible JSON schema: $schema" }
        buffer.toKString()
    }
}
