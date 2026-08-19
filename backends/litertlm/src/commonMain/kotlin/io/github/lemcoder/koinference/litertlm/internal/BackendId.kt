package io.github.lemcoder.koinference.litertlm.internal

/**
 * The integers `koilm_model_load` takes for its backend argument.
 *
 * Duplicated from koinference_litertlm_facade.h rather than imported, because only the cinterop
 * leg gets generated bindings for the enum — the JNI bridges marshal it as a plain int. The
 * native binding still imports the generated constants, and `BackendIdTest` compares the two, so
 * a change to the header that missed this file fails a test rather than selecting the wrong
 * backend at run time.
 */
internal object BackendId {
    const val CPU = 0
    const val GPU = 1
}
