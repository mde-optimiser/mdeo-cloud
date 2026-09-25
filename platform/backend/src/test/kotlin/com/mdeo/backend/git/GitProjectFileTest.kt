package com.mdeo.backend.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitProjectFileTest {
    @Test
    fun `parse reads the plugins array`() {
        val content = """{"plugins": ["/plugin/model/", "/plugin/csv/"]}""".toByteArray()

        assertEquals(listOf("/plugin/csv/", "/plugin/model/"), GitProjectFile.parse(content)?.plugins)
    }

    @Test
    fun `parse sorts so unordered input compares equal to sorted input`() {
        val a = GitProjectFile.parse("""{"plugins": ["/plugin/model/", "/plugin/csv/"]}""".toByteArray())
        val b = GitProjectFile.parse("""{"plugins": ["/plugin/csv/", "/plugin/model/"]}""".toByteArray())

        assertEquals(a, b)
    }

    @Test
    fun `parse of a file without a plugins key succeeds, describing no plugins`() {
        // Distinct from an empty array: a file that says nothing about
        // plugins must leave a project's plugins alone on push, where one
        // listing none is asking for none.
        val parsed = GitProjectFile.parse("""{"somethingElse": 1}""".toByteArray())

        assertNull(parsed?.plugins)
        assertEquals(GitProjectFile.Contents(plugins = null), parsed)
    }

    @Test
    fun `parse of an empty plugins array returns an empty list, not null`() {
        assertEquals(emptyList(), GitProjectFile.parse("""{"plugins": []}""".toByteArray())?.plugins)
    }

    @Test
    fun `parse returns null for a top level array`() {
        assertNull(GitProjectFile.parse("""["/plugin/model/"]""".toByteArray()))
    }

    @Test
    fun `parse returns null for malformed JSON`() {
        assertNull(GitProjectFile.parse("not json at all".toByteArray()))
    }

    @Test
    fun `parse returns null when plugins is not an array`() {
        assertNull(GitProjectFile.parse("""{"plugins": "/plugin/model/"}""".toByteArray()))
    }

    @Test
    fun `parse returns null for a nested structure in place of a url`() {
        assertNull(GitProjectFile.parse("""{"plugins": [{"not": "a url"}]}""".toByteArray()))
    }

    @Test
    fun `parse returns null for a non-string primitive in place of a url`() {
        // JSON parses these perfectly well, and reading them as their text
        // would turn a pushed `false` into the url "false": accepted, matched
        // against no registered plugin, and so silently clearing every plugin
        // the project had. Refused as malformed instead.
        assertNull(GitProjectFile.parse("""{"plugins": [false]}""".toByteArray()))
        assertNull(GitProjectFile.parse("""{"plugins": [1]}""".toByteArray()))
        assertNull(GitProjectFile.parse("""{"plugins": [null]}""".toByteArray()))
    }

    @Test
    fun `serialize then parse round-trips the same urls`() {
        val urls = listOf("/plugin/model/", "/plugin/csv/", "/plugin/config/")

        val roundTripped = GitProjectFile.parse(GitProjectFile.serialize(urls))

        assertEquals(urls.sorted(), roundTripped?.plugins)
    }

    @Test
    fun `serialize produces a file ending in a newline`() {
        assertTrue(String(GitProjectFile.serialize(listOf("/plugin/model/"))).endsWith("\n"))
    }

    @Test
    fun `describes is true when content lists exactly the given urls`() {
        val content = """{"plugins":["/plugin/model/","/plugin/csv/"]}""".toByteArray()

        assertTrue(GitProjectFile.describes(content, listOf("/plugin/csv/", "/plugin/model/")))
    }

    @Test
    fun `describes ignores formatting differences`() {
        // Same urls as the canonical serialize() output would produce, but
        // formatted the way a hand-edited push might arrive.
        val handEdited = """
            {
                "plugins": [ "/plugin/csv/",
                             "/plugin/model/" ]
            }
        """.trimIndent().toByteArray()

        assertTrue(GitProjectFile.describes(handEdited, listOf("/plugin/model/", "/plugin/csv/")))
    }

    @Test
    fun `describes accepts what serialize produces, so a fetch after a push adds no commit`() {
        val urls = listOf("/plugin/model/", "/plugin/csv/")

        assertTrue(GitProjectFile.describes(GitProjectFile.serialize(urls), urls))
    }

    @Test
    fun `describes is false when the url sets differ`() {
        val content = """{"plugins":["/plugin/model/"]}""".toByteArray()

        assertFalse(GitProjectFile.describes(content, listOf("/plugin/model/", "/plugin/csv/")))
    }

    @Test
    fun `describes is false for a file with no plugins key, even against no urls`() {
        assertFalse(GitProjectFile.describes("{}".toByteArray(), emptyList()))
    }

    @Test
    fun `describes is false for unparseable content regardless of urls`() {
        assertFalse(GitProjectFile.describes("not json".toByteArray(), emptyList()))
    }
}
