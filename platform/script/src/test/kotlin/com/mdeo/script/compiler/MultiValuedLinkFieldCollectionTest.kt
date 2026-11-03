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
 * Regression test for IncompatibleClassChangeError when calling ReadonlyCollection
 * interface methods (filter, map, sum, size) on multi-valued link fields.
 *
 * Bug: FieldGetterPropertyDefinition.emitAccess() returned the raw java.util.LinkedHashSet
 * (for link fields) without wrapping it in SetImpl. The generated INVOKEINTERFACE bytecode
 * for ReadonlyCollection methods (e.g. size(), filter()) failed at runtime because
 * java.util.LinkedHashSet does not implement ReadonlyCollection.
 *
 * Fix: emitAccess() now wraps multi-valued Set link fields in SetImpl so they implement
 * ReadonlyCollection, and wraps multi-valued List property fields in ListImpl.
 */
class MultiValuedLinkFieldCollectionTest {

    private val metamodelPath = "/test-multi-link.mm"
    private val classPackage = "${ScriptMetamodelTypeRegistrar.CLASS_PACKAGE}$metamodelPath"
    private val classContainerPackage = "${ScriptMetamodelTypeRegistrar.CLASS_CONTAINER_PACKAGE}$metamodelPath"
    private val compiler = ScriptCompiler()
    private val testFilePath = "test://multi-link.script"

    /**
     * Regression test: calling `.size()` on a multi-valued link field must not throw
     * IncompatibleClassChangeError.
     *
     * Equivalent script:
     * ```
     * fun test(): int {
     *     var total = 0
     *     for (sprint in Sprint.all()) {
     *         total = total + sprint.committedItems.size()
     *     }
     *     return total
     * }
     * ```
     */
    @Test
    fun `size on multi-valued link field returns correct count`() {
        val metamodelData = buildMetamodel()
        val ast = buildSizeAst()
        val input = CompilationInput(mapOf(testFilePath to ast))
        val program = compiler.compile(input, metamodelData)

        val modelData = buildModelData()
        val env = ExecutionEnvironment(program)
        val model = program.metamodel!!.loadModel(modelData)
        val context = SimpleScriptContext(System.out, model)

        val result = env.invoke(testFilePath, "test", context)
        assertEquals(2, result)
    }

    private fun buildMetamodel(): MetamodelData = MetamodelData(
        path = metamodelPath,
        classes = listOf(
            ClassData(
                name = "BacklogItem",
                isAbstract = false,
                properties = listOf(
                    PropertyData(
                        name = "effort",
                        primitiveType = "double",
                        multiplicity = MultiplicityData.single()
                    )
                )
            ),
            ClassData(name = "Sprint", isAbstract = false)
        ),
        associations = listOf(
            AssociationData(
                source = AssociationEndData(
                    className = "Sprint",
                    name = "committedItems",
                    multiplicity = MultiplicityData.many()
                ),
                operator = "--",
                target = AssociationEndData(
                    className = "BacklogItem",
                    name = "sprint",
                    multiplicity = MultiplicityData.optional()
                )
            )
        )
    )

    private fun buildModelData(): ModelData = ModelData(
        metamodelPath = metamodelPath,
        instances = listOf(
            ModelDataInstance(name = "sprint1", className = "Sprint", properties = emptyMap()),
            ModelDataInstance(
                name = "item1", className = "BacklogItem",
                properties = mapOf("effort" to ModelDataPropertyValue.NumberValue(3.0))
            ),
            ModelDataInstance(
                name = "item2", className = "BacklogItem",
                properties = mapOf("effort" to ModelDataPropertyValue.NumberValue(5.0))
            )
        ),
        links = listOf(
            ModelDataLink(
                sourceName = "sprint1", sourceProperty = "committedItems",
                targetName = "item1", targetProperty = "sprint"
            ),
            ModelDataLink(
                sourceName = "sprint1", sourceProperty = "committedItems",
                targetName = "item2", targetProperty = "sprint"
            )
        )
    )

    /**
     * Builds the AST for:
     * ```
     * fun test(): int {
     *     var total = 0
     *     for (sprint in Sprint.all()) {
     *         total = total + sprint.committedItems.size()
     *     }
     *     return total
     * }
     * ```
     */
    private fun buildSizeAst() = buildTypedAst {
        val intIdx = intType()
        val setIdx = addType(ClassTypeRef("builtin", "Set", false))
        val sprintIdx = addType(ClassTypeRef(classPackage, "Sprint", false))
        val collSprintIdx = addType(ClassTypeRef("builtin", "Collection", false))
        val sprintContIdx = addType(ClassTypeRef(classContainerPackage, "Sprint", false))

        // fun test(): int
        function(
            name = "test",
            returnType = intIdx,
            body = listOf(
                // var total = 0  (scope 3)
                varDecl("total", intIdx, intLiteral(0, intIdx)),
                // for (sprint in Sprint.all()) { ... }
                forStmt(
                    variableName = "sprint",
                    variableType = sprintIdx,
                    iterable = memberCall(
                        expression = identifier("Sprint", sprintContIdx, scope = 1),
                        member = "all",
                        overload = "",
                        arguments = emptyList(),
                        resultTypeIndex = collSprintIdx
                    ),
                    body = listOf(
                        // total = total + sprint.committedItems.size()
                        assignment(
                            left = identifier("total", intIdx, scope = 3),
                            right = binaryExpr(
                                left = identifier("total", intIdx, scope = 3),
                                operator = "+",
                                right = memberCall(
                                    expression = memberAccess(
                                        expression = identifier("sprint", sprintIdx, scope = 4),
                                        member = "committedItems",
                                        resultTypeIndex = setIdx
                                    ),
                                    member = "size",
                                    overload = "",
                                    arguments = emptyList(),
                                    resultTypeIndex = intIdx
                                ),
                                resultTypeIndex = intIdx
                            )
                        )
                    )
                ),
                returnStmt(identifier("total", intIdx, scope = 3))
            )
        )
    }
}
