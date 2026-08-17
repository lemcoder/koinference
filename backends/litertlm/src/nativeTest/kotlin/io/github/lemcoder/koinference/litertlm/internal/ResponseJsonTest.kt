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

    @Test
    fun aPartWhoseFieldsAreNotStringsIsSkippedRatherThanThrown() {
        // The documented contract is "empty for malformed", and it has to hold for a part
        // whose type or text is an object or an array too — jsonPrimitive throws on those,
        // which would come out of generateResponse as an IllegalArgumentException.
        assertEquals("", extractResponseText("""{"content":[{"type":"text","text":["a","b"]}]}"""))
        assertEquals("", extractResponseText("""{"content":[{"type":{"a":1},"text":"x"}]}"""))
        assertEquals("", extractResponseText("""{"content":{"type":"text"}}"""))
        // A good part alongside a bad one still comes through.
        assertEquals(
            "kept",
            extractResponseText("""{"content":[{"type":"text","text":{}},{"type":"text","text":"kept"}]}"""),
        )
    }
}
