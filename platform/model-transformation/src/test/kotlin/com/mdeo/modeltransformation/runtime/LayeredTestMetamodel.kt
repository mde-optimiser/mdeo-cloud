package com.mdeo.modeltransformation.runtime

import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.AssociationEndData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.metamodel.data.PropertyData
import com.mdeo.metamodel.data.merge

/**
 * A layered metamodel for the tests of transformations that run on models of a metamodel
 * importing theirs: `shop.mm` (layer 3) imports `base.mm` (layer 2).
 *
 * ```
 * // base.mm
 * class Component { name: string }
 * class Container { name: string }
 * Component.host[1] --> Container.components[*]
 *
 * // shop.mm
 * import "./base.mm"
 * class Service extends Component
 * class Server extends Container
 * class Store { name: string }
 * Service.store[0..1] --> Store
 * ```
 */
object LayeredTestMetamodel {
    const val BASE_PATH = "/arch/base.mm"
    const val SHOP_PATH = "/arch/shop.mm"

    private fun named(name: String, extends: List<String> = emptyList()) = ClassData(
        name, false, extends = extends,
        properties = listOf(PropertyData("name", primitiveType = "string", multiplicity = MultiplicityData.single()))
    )

    val base = MetamodelData(
        path = BASE_PATH,
        classes = listOf(named("Component"), named("Container")),
        associations = listOf(
            AssociationData(
                AssociationEndData("Component", "host", MultiplicityData.single()),
                "-->",
                AssociationEndData("Container", "components", MultiplicityData.many())
            )
        )
    )

    /** The merged layer-3 metamodel, as the backend loads it for a shop.mm model. */
    val shop: MetamodelData = MetamodelData.merge(
        MetamodelData(
            path = SHOP_PATH,
            classes = listOf(
                ClassData("Service", false, extends = listOf("Component")),
                ClassData("Server", false, extends = listOf("Container")),
                named("Store")
            ),
            associations = listOf(
                AssociationData(
                    AssociationEndData("Service", "store", MultiplicityData.optional()),
                    "-->",
                    AssociationEndData("Store", null, MultiplicityData.many())
                )
            ),
            importedMetamodelPaths = listOf(BASE_PATH)
        ),
        listOf(base)
    )
}
