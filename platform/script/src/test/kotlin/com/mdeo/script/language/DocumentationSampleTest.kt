package com.mdeo.script.language

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The script samples of the documentation run as the documentation describes.
 *
 * `language/documentation-records.json` is the typed AST of
 * `website/samples/language-tour/records.fn`, and `language/delivery-tours-<file>.json` the typed
 * AST of `website/samples/delivery-tours/<file>.fn`.
 */
class DocumentationSampleTest : FrontendAstTest() {

    private val deliveryTours = compile(
        mapOf(
            "/geometry.fn" to "delivery-tours-geometry",
            "/tours.fn" to "delivery-tours-tours",
            "/planning.fn" to "delivery-tours-planning"
        )
    )

    @Test
    fun `the records sample plans its route`() {
        assertEquals(7.0, run(compile("documentation-records"), "planRoute"))
    }

    @Test
    fun `the delivery tours sample reports its tours, shortest first`() {
        assertEquals(
            "west (empty): 0.0 km, 0.0 min, longest stop none\n" +
                "south: 10.0 km, 40.0 min, longest stop harbour\n" +
                "north: 20.0 km, 100.0 min, late, longest stop market\n",
            runIn(deliveryTours, "/planning.fn", "plan")
        )
    }

    @Test
    fun `the delivery tours sample keeps one of each equal depot`() {
        assertEquals(2, runIn(deliveryTours, "/planning.fn", "depots"))
    }

    @Test
    fun `the delivery tours sample compares and copies stops`() {
        assertEquals(
            "true false false Stop(name=bakery, location=Point(x=3.0, y=4.0), minutes=30)",
            runIn(deliveryTours, "/planning.fn", "copies")
        )
    }
}
