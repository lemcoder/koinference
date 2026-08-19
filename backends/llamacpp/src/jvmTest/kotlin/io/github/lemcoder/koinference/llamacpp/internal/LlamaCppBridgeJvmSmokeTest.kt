package io.github.lemcoder.koinference.llamacpp.internal

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exercises the generated JNI bridges against the real stub library, so a regression in the
 * generator's marshalling shows up as a failing test rather than at first inference.
 *
 * Needs no model: both cases here cross the boundary in both directions — a Kotlin string into C,
 * a status back — which is what the marshalling actually is. Everything above this seam is
 * covered by [io.github.lemcoder.koinference.llamacpp.LlamaCppRuntimeTest] with a fake bridge.
 */
class LlamaCppBridgeJvmSmokeTest {

    private val bridge = platformBridge()

    @Test
    fun `loading a missing model fails rather than returning a usable handle`() {
        val failure = assertFailsWith<IllegalStateException> {
            bridge.openModel(ModelOptions("/nonexistent/model.gguf"))
        }
        assertTrue(failure.message!!.contains("/nonexistent/model.gguf"))
    }

    @Test
    fun `a JSON schema converts to a grammar`() {
        // Needs no model, and it is the one call that returns a long string through the bridges.
        val grammar = bridge.jsonSchemaToGrammar("""{"type":"object","properties":{"a":{"type":"string"}}}""")
        assertTrue(grammar.contains("root"), "expected a GBNF grammar, got: $grammar")
    }

    @Test
    fun `an unconvertible schema is rejected`() {
        assertFailsWith<IllegalArgumentException> { bridge.jsonSchemaToGrammar("not a schema") }
    }
}
