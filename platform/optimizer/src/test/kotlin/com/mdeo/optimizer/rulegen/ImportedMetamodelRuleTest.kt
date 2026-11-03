package com.mdeo.optimizer.rulegen

import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.AssociationEndData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.metamodel.data.merge
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Mutation rules for a metamodel split over two files, `house.mm` importing `garage.mm`: the rules'
 * types name every class after the file declaring it, as hand-written transformations do.
 *
 * ```
 * // garage.mm
 * class Garage
 *
 * // house.mm
 * import "./garage.mm"
 * class House
 * House.garages[0..3] --> Garage
 * ```
 */
class ImportedMetamodelRuleTest {

    private val metamodelData = MetamodelData.merge(
        MetamodelData(
            path = "/project/house.mm",
            classes = listOf(ClassData("House", false)),
            associations = listOf(
                AssociationData(
                    AssociationEndData("House", "garages", MultiplicityData(0, 3)),
                    "-->",
                    AssociationEndData("Garage", null, MultiplicityData(0, 1))
                )
            ),
            importedMetamodelPaths = listOf("/project/garage.mm")
        ),
        listOf(MetamodelData(path = "/project/garage.mm", classes = listOf(ClassData("Garage", false))))
    )

    @Test
    fun `rule types name each class after its file`() {
        val info = MetamodelInfo(metamodelData)
        val ast = MutationAstBuilder.build(EdgeSpec("House", info.endOf("House", "garages")!!, EdgeKind.ADD), info)
        val packages = ast.types.filterIsInstance<ClassTypeRef>()
            .filter { it.type == "House" || it.type == "Garage" }
            .associate { it.type to it.`package` }
        assertEquals(mapOf("House" to "class/project/house.mm", "Garage" to "class/project/garage.mm"), packages)
    }
}
