package com.mdeo.script.compiler

import com.mdeo.script.runtime.ScriptRecord
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * The record classes [RecordClasses] generates, and the behaviour they inherit from [ScriptRecord].
 */
class RecordClassesTest {

    private class Loader : ClassLoader(RecordClassesTest::class.java.classLoader) {
        fun define(internalName: String, bytecode: ByteArray): Class<*> {
            val name = internalName.replace('/', '.')
            return defineClass(name, bytecode, 0, bytecode.size)
        }
    }

    private val loader = Loader()

    private val pointClass = loader.define(
        "test/record/Point",
        RecordClasses.generate("test/record/Point", "Point", listOf("x", "label"))
    )

    private val otherPointClass = loader.define(
        "test/record/OtherPoint",
        RecordClasses.generate("test/record/OtherPoint", "Point", listOf("x", "label"))
    )

    private fun point(vararg values: Any?, type: Class<*> = pointClass): ScriptRecord =
        type.getConstructor(Array<Any?>::class.java).newInstance(arrayOf(*values)) as ScriptRecord

    @Test
    fun `a generated record is a final script record`() {
        assertEquals(ScriptRecord::class.java, pointClass.superclass)
        assert(java.lang.reflect.Modifier.isFinal(pointClass.modifiers))
    }

    @Test
    fun `fields are read by position`() {
        val p = point(1.5, "a")
        assertEquals(1.5, p.field(0))
        assertEquals("a", p.field(1))
        assertEquals(listOf(1.5, "a"), p.fields())
        assertEquals("Point", p.recordName)
    }

    @Test
    fun `fields are set by position, including to null`() {
        val p = point(1.5, "a")
        ScriptRecord.set(p, 2.5, 0)
        ScriptRecord.set(p, null, 1)
        assertEquals(listOf(2.5, null), p.fields())
    }

    @Test
    fun `toString names the record and its fields in declaration order`() {
        assertEquals("Point(x=1.5, label=null)", point(1.5, null).toString())
    }

    @Test
    fun `a copy is a new record of the same class with its own field values`() {
        val tags = mutableListOf("t")
        val p = point(1.5, tags)
        val copy = p.copy()
        assertSame(pointClass, copy.javaClass)
        assertNotSame(p, copy)
        assertEquals(p, copy)
        ScriptRecord.set(copy, 9.0, 0)
        assertEquals(1.5, p.field(0))
        assertSame(tags, copy.field(1))
    }

    @Test
    fun `records are equal by class and field values, and hash accordingly`() {
        assertEquals(point(1.5, "a"), point(1.5, "a"))
        assertEquals(point(1.5, "a").hashCode(), point(1.5, "a").hashCode())
        assertNotEquals(point(1.5, "a"), point(1.5, "b"))
        assertNotEquals(point(1.5, "a"), point(1.5, "a", type = otherPointClass))
        assertNotEquals<Any>(point(1.5, "a"), listOf(1.5, "a"))
    }

    @Test
    fun `records with an equal field of a different box type are not equal`() {
        assertNotEquals(point(1, "a"), point(1L, "a"))
    }
}
