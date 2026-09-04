package io.github.lemcoder.koinference.onnx

import io.github.lemcoder.koinference.Koinference
import io.github.lemcoder.koinference.runtime.EmbeddingRuntime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Real embeddings, env-gated on `KOI_TEST_ONNX` (the `.onnx` path).
 *
 * What makes this worth running: unit-length vectors prove nothing on their own — a broken tokenizer
 * or the wrong pooling still yields unit vectors. Ranking does. If "a dog" is not closer to "a puppy"
 * than to "quantum chromodynamics", something upstream is wrong even though every assertion about
 * shapes would pass.
 */
class OnnxEmbeddingTest {

    private val model: String? = System.getenv("KOI_TEST_ONNX")

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        a.indices.forEach { dot += a[it].toDouble() * b[it] }
        return dot
    }

    @Test
    fun `embeds to unit vectors of the model's width`() {
        val path = model ?: return

        runBlocking {
            val koi = Koinference(Onnx)
            try {
                val runtime = koi.load(path) as EmbeddingRuntime
                val vectors = runtime.embed(listOf("hello world", "a second sentence"))

                assertEquals(2, vectors.size)
                assertEquals(384, runtime.dimensions)
                vectors.forEach { vector ->
                    assertEquals(384, vector.size)
                    // Normalised, so a dot product is a cosine — what every vector store assumes.
                    val length = kotlin.math.sqrt(vector.fold(0.0) { sum, v -> sum + v.toDouble() * v })
                    assertTrue(abs(length - 1.0) < 1e-3, "expected a unit vector, got length $length")
                }
            } finally {
                koi.unloadAll()
            }
        }
    }

    @Test
    fun `ranks related text above unrelated text`() {
        val path = model ?: return

        runBlocking {
            val koi = Koinference(Onnx)
            try {
                val runtime = koi.load(path) as EmbeddingRuntime
                val (dog, puppy, physics) = runtime
                    .embed(listOf("a dog runs in the park", "a puppy plays outside", "quantum chromodynamics"))
                    .let { Triple(it[0], it[1], it[2]) }

                val related = cosine(dog, puppy)
                val unrelated = cosine(dog, physics)

                println("bge cosine: dog~puppy ${"%.3f".format(related)}, dog~physics ${"%.3f".format(unrelated)}")
                assertTrue(
                    related > unrelated + 0.1,
                    "related $related should clearly beat unrelated $unrelated — a wrong tokenizer " +
                        "or the wrong pooling would still give unit vectors",
                )
            } finally {
                koi.unloadAll()
            }
        }
    }

    @Test
    fun `batching does not change a vector`() {
        val path = model ?: return

        runBlocking {
            val koi = Koinference(Onnx)
            try {
                val runtime = koi.load(path) as EmbeddingRuntime
                val alone = runtime.embed(listOf("a dog runs in the park")).single()
                // Padded to a longer neighbour: if padding leaked into pooling, this would drift.
                val batched = runtime.embed(
                    listOf("a dog runs in the park", "a considerably longer sentence to force padding"),
                ).first()

                assertTrue(
                    cosine(alone, batched) > 0.999,
                    "padding changed the vector: cosine ${cosine(alone, batched)}",
                )
            } finally {
                koi.unloadAll()
            }
        }
    }
}
