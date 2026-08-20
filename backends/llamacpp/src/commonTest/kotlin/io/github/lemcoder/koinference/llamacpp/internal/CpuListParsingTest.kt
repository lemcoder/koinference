package io.github.lemcoder.koinference.llamacpp.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CpuListParsingTest {

    @Test
    fun parsesRangesAndSingletons() {
        assertEquals(listOf(0, 1, 2, 3), parseCpuList("0-3"))
        assertEquals(listOf(0, 1, 2, 3, 5), parseCpuList("0-3,5"))
        assertEquals(listOf(4), parseCpuList("4"))
        assertEquals(listOf(0, 1, 8, 9), parseCpuList(" 0-1 , 8-9 "))
    }

    @Test
    fun toleratesRubbishRatherThanThrowing() {
        // These files are kernel-generated, but a parse failure here must not take down a load.
        assertTrue(parseCpuList("").isEmpty())
        assertTrue(parseCpuList("garbage").isEmpty())
        assertTrue(parseCpuList("5-2").isEmpty())
    }
}
