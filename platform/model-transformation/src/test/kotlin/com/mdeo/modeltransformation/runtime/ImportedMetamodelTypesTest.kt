package com.mdeo.modeltransformation.runtime

import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.AssociationEndData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.EnumData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.metamodel.data.PropertyData
import com.mdeo.metamodel.data.merge
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.compiler.ExpressionCompilerRegistry
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * A transformation on a metamodel split over two files, `shop.mm` importing `base.mm`: the
 * transformation's types name every class and enum after the file declaring it, as the
 * transformation language's type system does.
 *
 * ```
 * // base.mm
 * enum Unit { KG, PIECE }
 * class Item { weight: int  unit: Unit }
 *
 * // shop.mm
 * import "./base.mm"
 * class Box extends Item
 * Box.items[*] --> Item
 * ```
 */
class ImportedMetamodelTypesTest {

    private val metamodelData = MetamodelData.merge(
        MetamodelData(
            path = "/shop.mm",
            classes = listOf(ClassData("Box", false, extends = listOf("Item"))),
            associations = listOf(
                AssociationData(AssociationEndData("Box", "items", MultiplicityData.many()), "-->", AssociationEndData("Item", null, MultiplicityData.many()))
            ),
            importedMetamodelPaths = listOf("/base.mm")
        ),
        listOf(
            MetamodelData(
                path = "/base.mm",
                classes = listOf(
                    ClassData(
                        "Item", false,
                        properties = listOf(
                            PropertyData("weight", primitiveType = "int", multiplicity = MultiplicityData.single()),
                            PropertyData("unit", enumType = "Unit", multiplicity = MultiplicityData.single())
                        )
                    )
                ),
                enums = listOf(EnumData("Unit", listOf("KG", "PIECE")))
            )
        )
    )

    private val types = TinkerGraph.open().use { graph ->
        TransformationEngine(
            modelGraph = TinkerModelGraph.wrap(graph, Metamodel.compile(metamodelData)),
            ast = TypedAst(types = emptyList(), metamodelPath = "/shop.mm", statements = emptyList()),
            expressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
            statementExecutorRegistry = StatementExecutorRegistry.createDefaultRegistry()
        ).typeRegistry
    }

    private val item = ClassTypeRef("class/base.mm", "Item", false)
    private val box = ClassTypeRef("class/shop.mm", "Box", false)

    @Test
    fun `classes and enums are named after the file declaring them`() {
        assertTrue(types.hasType(item))
        assertTrue(types.hasType(box))
        assertTrue(types.hasType(ClassTypeRef("enum/base.mm", "Unit", false)))
        assertTrue(types.hasType(ClassTypeRef("enum-container/base.mm", "Unit", false)))
        assertFalse(types.hasType(ClassTypeRef("class/shop.mm", "Item", false)))
    }

    @Test
    fun `a class extends its superclass of another file`() {
        assertTrue(types.isSubtypeOf(box, item))
        assertNotNull(types.lookupProperty(box, "weight"))
        assertNotNull(types.lookupProperty(box, "items"))
    }
}
