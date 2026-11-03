package com.mdeo.optimizer.rulegen

import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.metamodel.data.ModelDataLink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MultiplicityCheckerTest {

    private fun model(objects: String, vararg links: String) = ModelData(
        "/test.mm",
        objects.split(" ").map { ModelDataInstance(it.substringBefore(":"), it.substringAfter(":"), emptyMap()) },
        links.map { l ->
            val (s, t) = l.split(" -- ")
            ModelDataLink(s.substringBefore("."), s.substringAfter(".", "").ifEmpty { null }, t.substringBefore("."), t.substringAfter(".", "").ifEmpty { null })
        }
    )

    private val info = MetamodelInfo(MetamodelBuilder("/test.mm").apply {
        cls("abstract Shape"); cls("Circle : Shape"); cls("Tag"); cls("Box"); cls("Shelf")
        assoc("Shape.tags[1..2]", "<-->", "Tag.shapes[*]")
        assoc("Box.items[*]", "*-->", "Circle.box[?]")
        assoc("Shelf.items[*]", "*-->", "Circle.shelf[?]")
        assoc("Tag.next[?]", "-->", "Tag")
    }.build())

    private fun violations(model: ModelData) = MultiplicityChecker.violations(model, info)

    @Test
    fun `a consistent model has no violations`() {
        assertEquals(listOf<String>(), violations(model("c:Circle t:Tag b:Box", "c.tags -- t.shapes", "b.items -- c.box", "t.next -- t")))
    }

    @Test
    fun `bounds are checked for inherited ends, in both directions of a link`() {
        assertEquals(listOf("c.tags has 0 link(s), outside [1..2]"), violations(model("c:Circle")))
        assertEquals(
            listOf("c.tags has 3 link(s), outside [1..2]"),
            violations(model("c:Circle t1:Tag t2:Tag t3:Tag", "c.tags -- t1.shapes", "t2.shapes -- c.tags", "c.tags -- t3.shapes"))
        )
    }

    @Test
    fun `an object may have only one whole`() {
        assertEquals(
            listOf("c is contained by 2 wholes"),
            violations(model("c:Circle t:Tag b:Box s:Shelf", "c.tags -- t.shapes", "b.items -- c.box", "s.items -- c.shelf"))
        )
    }

    @Test
    fun `links to unknown objects or of unknown associations are reported`() {
        assertEquals(listOf("link c.tags -- x.shapes names an unknown object", "c.tags has 0 link(s), outside [1..2]"),
            violations(model("c:Circle", "c.tags -- x.shapes")))
        assertEquals(listOf("link c.tags -- t.next matches no association", "c.tags has 0 link(s), outside [1..2]"),
            violations(model("c:Circle t:Tag", "c.tags -- t.next")))
    }

    @Test
    fun `only the given ends are checked when asked`() {
        val tags = info.endOf("Circle", "tags")!!
        val model = model("c:Circle b:Box s:Shelf", "b.items -- c.box", "s.items -- c.shelf")
        assertEquals(listOf("c.tags has 0 link(s), outside [1..2]"), MultiplicityChecker.violations(model, info, listOf(tags)))
        assertEquals(listOf<String>(), MultiplicityChecker.violations(model, info, listOf(info.endOf("Tag", "next")!!)))
    }
}
