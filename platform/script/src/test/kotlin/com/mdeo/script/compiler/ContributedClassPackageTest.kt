package com.mdeo.script.compiler

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ContributedClassPackageTest {

    @Test
    fun `contribution ids that differ only in punctuation get different packages`() {
        val segments = listOf("a-b", "a.b", "a_b", "a__b", "ab").map { ContributedClassCompiler.packageSegment(it) }
        assertEquals(segments.size, segments.toSet().size, "got $segments")
    }

    @Test
    fun `a plain id stays readable`() {
        assertEquals("geo", ContributedClassCompiler.packageSegment("geo"))
    }
}
