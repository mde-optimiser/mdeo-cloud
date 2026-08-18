package com.mdeo.backend.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitPluginsFileTest {
    @Test
    fun `parse reads a plain array of urls`() {
        val content = """["/plugin/model/", "/plugin/csv/"]""".toByteArray()

        assertEquals(listOf("/plugin/csv/", "/plugin/model/"), GitPluginsFile.parse(content))
    }

    @Test
    fun `parse sorts so unordered input compares equal to sorted input`() {
        val a = GitPluginsFile.parse("""["/plugin/model/", "/plugin/csv/"]""".toByteArray())
        val b = GitPluginsFile.parse("""["/plugin/csv/", "/plugin/model/"]""".toByteArray())

        assertEquals(a, b)
    }

    @Test
    fun `parse returns null for content that is not a JSON array`() {
        assertNull(GitPluginsFile.parse("""{"not": "an array"}""".toByteArray()))
    }

    @Test
    fun `parse returns null for malformed JSON`() {
        assertNull(GitPluginsFile.parse("not json at all".toByteArray()))
    }

    @Test
    fun `parse of a nested structure returns null`() {
        // Genuinely malformed as a list of urls, unlike a plain array of
        // non-string primitives, which JSON parses fine and is left to be
        // rejected downstream when nothing matches a registered plugin.
        assertNull(GitPluginsFile.parse("""[{"not": "a url"}]""".toByteArray()))
    }

    @Test
    fun `parse of an empty array returns an empty list, not null`() {
        assertEquals(emptyList(), GitPluginsFile.parse("[]".toByteArray()))
    }

    @Test
    fun `serialize then parse round-trips the same urls`() {
        val urls = listOf("/plugin/model/", "/plugin/csv/", "/plugin/config/")

        val roundTripped = GitPluginsFile.parse(GitPluginsFile.serialize(urls))

        assertEquals(urls.sorted(), roundTripped)
    }

    @Test
    fun `describes is true when content lists exactly the given urls`() {
        val content = """["/plugin/model/","/plugin/csv/"]""".toByteArray()

        assertTrue(GitPluginsFile.describes(content, listOf("/plugin/csv/", "/plugin/model/")))
    }

    @Test
    fun `describes ignores formatting differences`() {
        // Same urls as the canonical serialize() output would produce, but
        // pretty-printed the way a hand-edited push might arrive.
        val prettyPrinted = """
            [
              "/plugin/csv/",
              "/plugin/model/"
            ]
        """.trimIndent().toByteArray()

        assertTrue(GitPluginsFile.describes(prettyPrinted, listOf("/plugin/model/", "/plugin/csv/")))
    }

    @Test
    fun `describes is false when the url sets differ`() {
        val content = """["/plugin/model/"]""".toByteArray()

        assertFalse(GitPluginsFile.describes(content, listOf("/plugin/model/", "/plugin/csv/")))
    }

    @Test
    fun `describes is false for unparseable content regardless of urls`() {
        assertFalse(GitPluginsFile.describes("not json".toByteArray(), emptyList()))
    }
}
