package io.github.lemcoder.koinference.litertlm.internal

import kotlin.test.Test
import kotlin.test.assertEquals

class ResponseJsonTest {

    // The strings below are verbatim replies from the facade smoke test against
    // SmolLM2-135M-Instruct, not invented shapes.

    @Test
    fun readsTheTextPartOfAReply() {
        val raw = """{"role":"assistant","content":[{"type":"text","text":"Hello. How can I help you today?"}]}"""
        assertEquals("Hello. How can I help you today?", extractResponseText(raw))
    }

    @Test
    fun aConstrainedReplyCarriesItsJsonDocumentAsText() {
        val raw = """{"role":"assistant","content":[{"type":"text","text":"{\"city\":\"New York City\"}"}]}"""
        assertEquals("""{"city":"New York City"}""", extractResponseText(raw))
    }

    @Test
    fun concatenatesSeveralTextParts() {
        val raw = """{"role":"assistant","content":[{"type":"text","text":"one "},{"type":"text","text":"two"}]}"""
        assertEquals("one two", extractResponseText(raw))
    }

    @Test
    fun skipsNonTextParts() {
        val raw = """{"role":"assistant","content":[{"type":"image","path":"/a.png"},{"type":"text","text":"described"}]}"""
        assertEquals("described", extractResponseText(raw))
    }

    @Test
    fun acceptsAPlainStringContent() {
        assertEquals("bare", extractResponseText("""{"role":"assistant","content":"bare"}"""))
    }

    @Test
    fun returnsEmptyForFailureAndMalformedInput() {
        assertEquals("", extractResponseText(""))
        assertEquals("", extractResponseText("not json"))
        assertEquals("", extractResponseText("""{"role":"assistant"}"""))
    }
}
