package com.mdeo.script.compiler

import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.AssociationEndData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.metamodel.data.ModelDataLink
import com.mdeo.metamodel.data.ModelDataPropertyValue
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.metamodel.data.PropertyData
import com.mdeo.metamodel.data.merge
import com.mdeo.script.compiler.model.ScriptMetamodelTypeRegistrar
import com.mdeo.script.runtime.ExecutionEnvironment
import com.mdeo.script.runtime.SimpleScriptContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Scripts on a metamodel split over two files: `shop.mm` imports `base.mm`. The script's types
 * name every class after the file declaring it, as the script language's type system does, so
 * the merged metamodel must register them under those names.
 *
 * ```
 * // base.mm
 * class Item { weight: int }
 *
 * // shop.mm
 * import "./base.mm"
 * class Box extends Item
 * Box.items[*] --> Item
 * ```
 */
class ImportedMetamodelScriptTest {

    private val basePath = "/base.mm"
    private val shopPath = "/shop.mm"
    private val testFilePath = "test://imports.script"

    private val metamodel = MetamodelData.merge(
        MetamodelData(
            path = shopPath,
            classes = listOf(ClassData("Box", false, extends = listOf("Item"))),
            associations = listOf(
                AssociationData(
                    source = AssociationEndData("Box", "items", MultiplicityData.many()),
                    operator = "-->",
                    target = AssociationEndData("Item", null, MultiplicityData.many())
                )
            ),
            importedMetamodelPaths = listOf(basePath)
        ),
        listOf(
            MetamodelData(
                path = basePath,
                classes = listOf(
                    ClassData("Item", false, properties = listOf(PropertyData("weight", primitiveType = "int", multiplicity = MultiplicityData.single())))
                )
            )
        )
    )

    private val model = ModelData(
        metamodelPath = shopPath,
        instances = listOf(
            ModelDataInstance("box", "Box", mapOf("weight" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance("apple", "Item", mapOf("weight" to ModelDataPropertyValue.NumberValue(2.0))),
            ModelDataInstance("pear", "Item", mapOf("weight" to ModelDataPropertyValue.NumberValue(3.0)))
        ),
        links = listOf(
            ModelDataLink("box", "items", "apple", null),
            ModelDataLink("box", "items", "pear", null)
        )
    )

    /**
     * ```
     * fun test(): int {
     *     var total = 0
     *     for (item in Item.all()) {
     *         total = total + item.weight
     *     }
     *     for (box in Box.all()) {
     *         total = total + box.items.size() * 100
     *     }
     *     return total
     * }
     * ```
     */
    private fun ast() = buildTypedAst {
        val intIdx = intType()
        val setIdx = addType(ClassTypeRef("builtin", "Set", false))
        val collectionIdx = addType(ClassTypeRef("builtin", "Collection", false))
        val itemIdx = addType(ClassTypeRef("${ScriptMetamodelTypeRegistrar.CLASS_PACKAGE}$basePath", "Item", false))
        val itemContainerIdx = addType(ClassTypeRef("${ScriptMetamodelTypeRegistrar.CLASS_CONTAINER_PACKAGE}$basePath", "Item", false))
        val boxIdx = addType(ClassTypeRef("${ScriptMetamodelTypeRegistrar.CLASS_PACKAGE}$shopPath", "Box", false))
        val boxContainerIdx = addType(ClassTypeRef("${ScriptMetamodelTypeRegistrar.CLASS_CONTAINER_PACKAGE}$shopPath", "Box", false))
        val total = { identifier("total", intIdx, scope = 3) }

        function(
            name = "test",
            returnType = intIdx,
            body = listOf(
                varDecl("total", intIdx, intLiteral(0, intIdx)),
                forStmt(
                    variableName = "item",
                    variableType = itemIdx,
                    iterable = memberCall(
                        expression = identifier("Item", itemContainerIdx, scope = 1),
                        member = "all",
                        overload = "",
                        arguments = emptyList(),
                        resultTypeIndex = collectionIdx
                    ),
                    body = listOf(
                        assignment(
                            left = total(),
                            right = binaryExpr(
                                left = total(),
                                operator = "+",
                                right = memberAccess(identifier("item", itemIdx, scope = 4), "weight", resultTypeIndex = intIdx),
                                resultTypeIndex = intIdx
                            )
                        )
                    )
                ),
                forStmt(
                    variableName = "box",
                    variableType = boxIdx,
                    iterable = memberCall(
                        expression = identifier("Box", boxContainerIdx, scope = 1),
                        member = "all",
                        overload = "",
                        arguments = emptyList(),
                        resultTypeIndex = collectionIdx
                    ),
                    body = listOf(
                        assignment(
                            left = total(),
                            right = binaryExpr(
                                left = total(),
                                operator = "+",
                                right = binaryExpr(
                                    left = memberCall(
                                        expression = memberAccess(identifier("box", boxIdx, scope = 4), "items", resultTypeIndex = setIdx),
                                        member = "size",
                                        overload = "",
                                        arguments = emptyList(),
                                        resultTypeIndex = intIdx
                                    ),
                                    operator = "*",
                                    right = intLiteral(100, intIdx),
                                    resultTypeIndex = intIdx
                                ),
                                resultTypeIndex = intIdx
                            )
                        )
                    )
                ),
                returnStmt(total())
            )
        )
    }

    @Test
    fun `classes of an imported file are found, read and navigated under that file's types`() {
        val program = ScriptCompiler().compile(CompilationInput(mapOf(testFilePath to ast())), metamodel)
        val loaded = program.metamodel!!.loadModel(model)
        val result = ExecutionEnvironment(program).invoke(testFilePath, "test", SimpleScriptContext(System.out, loaded))
        // weights 5 + 2 + 3, and the two items of the box
        assertEquals(210, result)
    }
}
