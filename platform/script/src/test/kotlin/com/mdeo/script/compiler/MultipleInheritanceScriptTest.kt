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
import com.mdeo.script.compiler.model.ScriptMetamodelTypeRegistrar
import com.mdeo.script.runtime.ExecutionEnvironment
import com.mdeo.script.runtime.SimpleScriptContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Scripts on a metamodel whose classes have several superclasses. An instance of `Box` is a
 * `Sized` through its second superclass: typed as `Sized` it must be found by `Sized.all()`, pass
 * `is Sized`, and give its `size` and `items`.
 *
 * ```
 * abstract class Named { name: string }
 * abstract class Sized { size: int }
 * class Box extends Named, Sized
 * class Item
 * Sized.items[*] --> Item
 * ```
 */
class MultipleInheritanceScriptTest {

    private val metamodelPath = "/test-mi.mm"
    private val classPackage = "${ScriptMetamodelTypeRegistrar.CLASS_PACKAGE}$metamodelPath"
    private val classContainerPackage = "${ScriptMetamodelTypeRegistrar.CLASS_CONTAINER_PACKAGE}$metamodelPath"
    private val testFilePath = "test://mi.script"

    private val metamodel = MetamodelData(
        path = metamodelPath,
        classes = listOf(
            ClassData(
                "Named", true,
                properties = listOf(PropertyData("name", primitiveType = "string", multiplicity = MultiplicityData.single()))
            ),
            ClassData(
                "Sized", true,
                properties = listOf(PropertyData("size", primitiveType = "int", multiplicity = MultiplicityData.single()))
            ),
            ClassData("Box", false, extends = listOf("Named", "Sized")),
            ClassData("Item", false)
        ),
        associations = listOf(
            AssociationData(
                source = AssociationEndData("Sized", "items", MultiplicityData.many()),
                operator = "-->",
                target = AssociationEndData("Item", null, MultiplicityData.many())
            )
        )
    )

    private val model = ModelData(
        metamodelPath = metamodelPath,
        instances = listOf(
            ModelDataInstance(
                "small", "Box",
                mapOf("name" to ModelDataPropertyValue.StringValue("small"), "size" to ModelDataPropertyValue.NumberValue(2.0))
            ),
            ModelDataInstance(
                "large", "Box",
                mapOf("name" to ModelDataPropertyValue.StringValue("large"), "size" to ModelDataPropertyValue.NumberValue(5.0))
            ),
            ModelDataInstance("apple", "Item", emptyMap()),
            ModelDataInstance("pear", "Item", emptyMap())
        ),
        links = listOf(
            ModelDataLink("large", "items", "apple", null),
            ModelDataLink("large", "items", "pear", null)
        )
    )

    /**
     * ```
     * fun test(): int {
     *     var total = 0
     *     for (sized in Sized.all()) {
     *         if (sized is Sized) {
     *             total = total + sized.size + sized.items.size() * 100
     *         }
     *     }
     *     return total
     * }
     * ```
     */
    private fun ast() = buildTypedAst {
        val intIdx = intType()
        val booleanIdx = booleanType()
        val setIdx = addType(ClassTypeRef("builtin", "Set", false))
        val sizedIdx = addType(ClassTypeRef(classPackage, "Sized", false))
        val collectionIdx = addType(ClassTypeRef("builtin", "Collection", false))
        val sizedContainerIdx = addType(ClassTypeRef(classContainerPackage, "Sized", false))
        val sized = { identifier("sized", sizedIdx, scope = 4) }
        val total = { identifier("total", intIdx, scope = 3) }

        function(
            name = "test",
            returnType = intIdx,
            body = listOf(
                varDecl("total", intIdx, intLiteral(0, intIdx)),
                forStmt(
                    variableName = "sized",
                    variableType = sizedIdx,
                    iterable = memberCall(
                        expression = identifier("Sized", sizedContainerIdx, scope = 1),
                        member = "all",
                        overload = "",
                        arguments = emptyList(),
                        resultTypeIndex = collectionIdx
                    ),
                    body = listOf(
                        ifStmt(
                            condition = typeCheck(sized(), sizedIdx, booleanIdx),
                            thenBlock = listOf(
                                assignment(
                                    left = total(),
                                    right = binaryExpr(
                                        left = binaryExpr(
                                            left = total(),
                                            operator = "+",
                                            right = memberAccess(sized(), "size", resultTypeIndex = intIdx),
                                            resultTypeIndex = intIdx
                                        ),
                                        operator = "+",
                                        right = binaryExpr(
                                            left = memberCall(
                                                expression = memberAccess(sized(), "items", resultTypeIndex = setIdx),
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
                        )
                    )
                ),
                returnStmt(total())
            )
        )
    }

    @Test
    fun `an instance inheriting through its second superclass is found, checked and read as that class`() {
        val program = ScriptCompiler().compile(CompilationInput(mapOf(testFilePath to ast())), metamodel)
        val loaded = program.metamodel!!.loadModel(model)
        val result = ExecutionEnvironment(program).invoke(testFilePath, "test", SimpleScriptContext(System.out, loaded))
        // sizes 2 + 5, and the two items of the large box
        assertEquals(207, result)
    }
}
