package io.github.lemcoder.koinference.litertlm.internal

import io.github.lemcoder.koinference.Accelerator
import io.github.lemcoder.koinference.litertlm.jni.kniBridge0
import io.github.lemcoder.koinference.litertlm.jni.kniBridge1
import io.github.lemcoder.koinference.litertlm.jni.kniBridge10
import io.github.lemcoder.koinference.litertlm.jni.kniBridge11
import io.github.lemcoder.koinference.litertlm.jni.kniBridge2
import io.github.lemcoder.koinference.litertlm.jni.kniBridge4
import io.github.lemcoder.koinference.litertlm.jni.kniBridge5
import io.github.lemcoder.koinference.litertlm.jni.kniBridge6
import io.github.lemcoder.koinference.litertlm.jni.kniBridge7
import io.github.lemcoder.koinference.litertlm.jni.kniBridge8
import io.github.lemcoder.koinference.litertlm.jni.kniBridge9
import io.github.lemcoder.koinference.litertlm.jni.kniCString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Android binds the same C facade the Apple leg does, through bridges the Konan plugin generates
// from koinference_litertlm_facade.h. It used to go through Google's Kotlin API, because the
// Maven AAR's liblitertlm_jni.so exports only its own Java_* entry points and no litert_lm_*
// symbols — there was nothing for a facade to link against. The C API archive published at
// 0.16.0 exports 144 of them, so the two legs now differ only in how they reach the same
// functions: cinterop there, JNI here.
//
// Losing the Kotlin API also loses a coupling worth being rid of: its Flow overload of
// sendMessageAsync called a kotlinx-coroutines synthetic that 1.10.x no longer has, and died
// with NoSuchMethodError on device. A C API cannot drift with a Kotlin library's version.

/** Layout of KoiLmSessionParams: six 4-byte fields, no padding. */
internal const val SESSION_PARAMS_SIZE = 24
/** One chunk is a token or a few; the facade errors rather than truncating past this. */
internal const val CHUNK_BYTES = 512
/** First guess at a reply's size; a longer one is collected with koilm_last_response. */
internal const val INITIAL_REPLY_BYTES = 8192
internal actual fun platformBridge(): LiteRtLmBridge = JniBridge
internal fun lastError(): String = kniCString(kniBridge0()).orEmpty()
