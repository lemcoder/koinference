package io.github.lemcoder.koinference.benchmark.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetrieverTest {

    private fun corpus() = Retriever(
        listOf(
            Retriever.Passage("a", "alpha", floatArrayOf(1f, 0f, 0f)),
            Retriever.Passage("b", "beta", floatArrayOf(0f, 1f, 0f)),
            Retriever.Passage("c", "gamma", floatArrayOf(1f, 1f, 0f)),
        ),
    )

    @Test
    fun `cosine is 1 for identical direction and 0 for orthogonal`() {
        assertEquals(1f, cosineSimilarity(floatArrayOf(2f, 0f), floatArrayOf(5f, 0f)))
        assertEquals(0f, cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)))
    }

    @Test
    fun `a zero vector ranks neutral rather than dividing by zero`() {
        assertEquals(0f, cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)))
    }

    @Test
    fun `topK returns the most similar passages first`() {
        val hits = corpus().topK(floatArrayOf(1f, 0f, 0f), 2)
        assertEquals(listOf("a", "c"), hits.map { it.passage.id })
        assertTrue(hits[0].score >= hits[1].score)
    }

    @Test
    fun `k of zero retrieves nothing`() {
        assertTrue(corpus().topK(floatArrayOf(1f, 0f, 0f), 0).isEmpty())
    }

    @Test
    fun `augment prepends retrieved context and is a no-op with no hits`() {
        val r = corpus()
        val query = "what is alpha?"
        assertEquals(query, r.augment(query, emptyList()))
        val augmented = r.augment(query, r.topK(floatArrayOf(1f, 0f, 0f), 1))
        assertTrue(augmented.contains("alpha"))
        assertTrue(augmented.contains(query))
        assertTrue(augmented.length > query.length)
    }
}
