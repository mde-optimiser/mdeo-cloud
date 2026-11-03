package com.mdeo.optimizer.rulegen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MetamodelInfoTest {

    private fun AssociationEnd.text() = toString()

    @Nested
    inner class Ends {

        @Test
        fun `an association has an end at each side, each the other's opposite`() {
            val info = MetamodelInfo(mm("A", "B") { assoc("A.e[1..3]", "<-->", "B.f[?]") })
            val e = info.endOf("A", "e")!!
            val f = info.endOf("B", "f")!!
            assertSame(f, e.opposite)
            assertSame(e, f.opposite)
            assertEquals("A.e[1..3] -> B", e.text())
            assertEquals("B.f[0..1] -> A", f.text())
            assertTrue(e.isSourceSide)
            assertFalse(f.isSourceSide)
            assertEquals(e.association, f.association)
            assertFalse(e.isFixed)
            assertTrue(e.isBounded)
        }

        @Test
        fun `an unnamed end is unconstrained and cannot be looked up`() {
            val info = MetamodelInfo(mm("A", "B") { assoc("A.e[2]", "-->", "B") })
            val opposite = info.endOf("A", "e")!!.opposite
            assertEquals(null, opposite.name)
            assertEquals(0, opposite.lower)
            assertEquals(-1, opposite.upper)
            assertEquals(listOf<AssociationEnd>(), info.endsOf("B"))
            assertEquals(2, info.allEnds.size)
        }

        @Test
        fun `the whole of a composition holds the containment end in every notation`() {
            for (operator in listOf("*-->", "*--", "<--*", "--*")) {
                val metamodel = when (operator) {
                    "*-->" -> mm("W", "P") { assoc("W.parts[*]", operator, "P.whole[?]") }
                    "*--" -> mm("W", "P") { assoc("W", operator, "P.whole[?]") }
                    "<--*" -> mm("W", "P") { assoc("P.whole[?]", operator, "W.parts[*]") }
                    else -> mm("W", "P") { assoc("P.whole[?]", operator, "W") }
                }
                val info = MetamodelInfo(metamodel)
                val container = info.endOf("P", "whole")!!
                assertTrue(container.isContainer, operator)
                assertFalse(container.isContainment, operator)
                assertTrue(container.opposite.isContainment, operator)
                assertEquals("W", container.target, operator)
                assertEquals(listOf("W"), info.containerContextsOf("P").map { it.wholeClass }, operator)
            }
        }

        @Test
        fun `a plain association is neither containment nor container`() {
            val info = MetamodelInfo(mm("A", "B") { assoc("A.e[*]", "<-->", "B.f[*]") })
            assertTrue(info.allEnds.none { it.isContainment || it.isContainer })
        }

        @Test
        fun `bounds are written as the language writes them`() {
            assertEquals("[0..*]", MetamodelInfo.bounds(0, -1))
            assertEquals("[2..2]", MetamodelInfo.bounds(2, 2))
        }
    }

    @Nested
    inner class Inheritance {

        private val info = MetamodelInfo(MetamodelBuilder("/test.mm").apply {
            cls("abstract Named"); cls("abstract Shape : Named"); cls("Circle : Shape"); cls("Square : Shape"); cls("Tag : Named")
            cls("Canvas")
            assoc("Shape.tags[*]", "<-->", "Tag.shapes[*]")
            assoc("Square.corner[?]", "-->", "Tag")
            assoc("Canvas.shapes[*]", "*-->", "Shape.canvas[?]")
            assoc("Canvas.circles[0..2]", "*-->", "Circle.circleCanvas[?]")
        }.build())

        @Test
        fun `super- and subtypes are transitive and include the class`() {
            assertEquals(setOf("Circle", "Shape", "Named"), info.supertypesOf("Circle"))
            assertEquals(setOf("Named", "Shape", "Circle", "Square", "Tag"), info.subtypesOf("Named"))
            assertTrue(info.compatible("Shape", "Circle"))
            assertTrue(info.compatible("Circle", "Named"))
            assertFalse(info.compatible("Circle", "Square"))
            assertEquals(listOf("Circle", "Square", "Tag"), info.concreteSubtypesOf("Named"))
        }

        @Test
        fun `a class holds the ends of its superclasses`() {
            assertEquals(setOf("tags", "canvas", "circleCanvas"), info.endsOf("Circle").map { it.name }.toSet())
            assertEquals(setOf("tags", "canvas", "corner"), info.endsOf("Square").map { it.name }.toSet())
            assertEquals("Shape", info.endOf("Square", "tags")!!.owner)
            assertEquals(null, info.endOf("Circle", "corner"))
        }

        @Test
        fun `ends pointing at a class include those declared for its relatives`() {
            assertEquals(setOf("Tag.shapes", "Canvas.shapes", "Canvas.circles"), info.endsPointingAt("Circle").map { "${it.owner}.${it.name}" }.toSet())
            assertEquals(setOf("Tag.shapes", "Canvas.shapes", "Canvas.circles"), info.endsPointingAt("Shape").map { "${it.owner}.${it.name}" }.toSet())
        }

        @Test
        fun `containers are found through superclasses and for subclasses`() {
            assertEquals(setOf("Canvas.shapes", "Canvas.circles"), info.containerContextsOf("Circle").map { "${it.wholeClass}.${it.containmentEnd.name}" }.toSet())
            assertEquals(listOf("Canvas.shapes"), info.containerContextsOf("Square").map { "${it.wholeClass}.${it.containmentEnd.name}" })
            assertEquals(setOf("canvas", "circleCanvas"), info.containerEndsCompatibleWith("Shape").map { it.name }.toSet())
        }
    }

    @Nested
    inner class Refinements {

        private val metamodel = MetamodelBuilder("/test.mm").apply {
            cls("abstract P"); cls("A : P", attr("size", "int")); cls("B")
            assoc("P.bs[0..5]", "<-->", "B.p[*]")
            assoc("A.c[1..*]", "-->", "B")
        }.build()

        private fun info(vararg refinements: String) =
            MetamodelInfo(metamodel, refinements.map { refine(it) }.map { MultiplicityOverride(it.className, it.fieldName, it.lower, it.upper) })

        private fun error(vararg refinements: String) = assertThrows<RuleGenerationException> { info(*refinements) }.message!!

        @Test
        fun `refined bounds replace the metamodel's at that end only`() {
            val info = info("P.bs[1..2]", "A.c[2..3]")
            assertEquals("P.bs[1..2] -> B", info.endOf("A", "bs")!!.text())
            assertEquals("B.p[0..*] -> P", info.endOf("B", "p")!!.text())
            assertEquals("A.c[2..3] -> B", info.endOf("A", "c")!!.text())
            assertEquals(listOf("bs", "c"), info.refinedEnds.map { it.name })
        }

        @Test
        fun `a refinement may keep the bounds or narrow them to one count`() {
            assertEquals("P.bs[0..5] -> B", info("P.bs[0..5]").endOf("A", "bs")!!.text())
            assertTrue(info("P.bs[3]").endOf("A", "bs")!!.isFixed)
            assertTrue(info("A.c[4..*]").endOf("A", "c")!!.lower == 4)
        }

        @Test
        fun `invalid refinements are rejected with the reason`() {
            assertTrue(error("Q.bs[1]").contains("unknown class 'Q'"))
            assertTrue(error("P.foo[1]").contains("no association end 'foo'"))
            assertTrue(error("A.size[1]").contains("is an attribute"))
            assertTrue(error("A.bs[1]").contains("declared on 'P'; refine it there"))
            assertTrue(error("P.bs[0..6]").contains("does not lie within the metamodel's [0..5]"))
            assertTrue(error("P.bs[1..*]").contains("does not lie within"))
            assertTrue(error("A.c[0..3]").contains("does not lie within the metamodel's [1..*]"))
            assertTrue(error("P.bs[3..2]").contains("invalid bounds"))
            assertTrue(error("P.bs[0]").contains("invalid bounds"))
            assertTrue(error("P.bs[1]", "P.bs[2]").contains("refined more than once"))
        }
    }
}
