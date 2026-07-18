package com.mdeo.modeltransformation.runtime

import com.mdeo.expression.ast.expressions.TypedBinaryExpression
import com.mdeo.expression.ast.expressions.TypedIdentifierExpression
import com.mdeo.expression.ast.expressions.TypedIntLiteralExpression
import com.mdeo.expression.ast.expressions.TypedMemberAccessExpression
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.VoidType
import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.metamodel.data.ModelDataPropertyValue
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.metamodel.data.PropertyData
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternPropertyAssignment
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.compiler.ExpressionCompilerRegistry
import com.mdeo.modeltransformation.compiler.registry.TypeRegistry
import com.mdeo.modeltransformation.compiler.registry.gremlinType
import com.mdeo.modeltransformation.graph.mdeo.MdeoModelGraph
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test reproducing the runtime failure of arithmetic assignments into
 * integer metamodel fields when the model is backed by [MdeoModelGraph].
 *
 * ## Bug
 *
 * Arithmetic expressions such as `occupiedBeds = occupiedBeds + 1` are compiled to the
 * Gremlin `math()` step, which always produces a `Double`. [MdeoModelGraph] delegates
 * property writes to the ASM-generated `ModelInstance.setPropertyByKey`, whose bytecode
 * previously performed a hard `CHECKCAST java/lang/Integer` for `int` fields. Writing the
 * `Double` result therefore threw:
 *
 *     java.lang.ClassCastException: class java.lang.Double cannot be cast to class
 *     java.lang.Integer
 *
 * The graph-modification layer ([com.mdeo.modeltransformation.runtime.match.GraphModificationApplier])
 * only narrows the numeric result when the target instance's declared class name is known.
 * When an already-matched instance is *referenced* in an update block (no `: Type` annotation,
 * i.e. `stayDayUpdate { occupiedBeds = stayDayUpdate.occupiedBeds + 1 }`), the class name is
 * absent, the pre-narrowing is skipped, and the raw `Double` reaches the model boundary.
 *
 * ## Fix
 *
 * `setPropertyByKey` now coerces any incoming number to the declared JVM field type via
 * `java.lang.Number`, mirroring the model-load path (`Metamodel.convertNumber`).
 */
class ArithmeticIntFieldAssignmentTest {

    private val className = "BedAvailability"
    private val propertyName = "occupiedBeds"

    private val metamodelData = MetamodelData(
        classes = listOf(
            ClassData(
                name = className,
                isAbstract = false,
                extends = emptyList(),
                properties = listOf(
                    PropertyData(
                        name = propertyName,
                        primitiveType = "int",
                        multiplicity = MultiplicityData.single()
                    )
                )
            )
        )
    )

    private val metamodel = Metamodel.compile(metamodelData)

    private val occupiedBedsKey: String =
        "prop_${metamodel.metadata.classes[className]!!.propertyFields[propertyName]!!.fieldIndex}"

    // Type index layout for the AST:
    //   0 → void
    //   1 → builtin.int
    //   2 → the receiver class type (BedAvailability)
    private val receiverType = ClassTypeRef(`package` = "builtin", type = className, isNullable = false)
    private val types: List<ReturnType> = listOf(
        VoidType(),
        ClassTypeRef(`package` = "builtin", type = "int", isNullable = false),
        receiverType
    )

    /**
     * Registers the receiver class and its `occupiedBeds` graph property so that member
     * access (`bed.occupiedBeds`) can be resolved during compilation.
     */
    private fun registerTypes() {
        val type = gremlinType("builtin", className)
            .graphProperty(propertyName, occupiedBedsKey)
            .build()
        TypeRegistry.GLOBAL.register(type)
    }

    /**
     * Builds an AST equivalent to:
     *
     *     match {
     *         bed: BedAvailability {}
     *         bed { occupiedBeds = bed.occupiedBeds + 1 }
     *     }
     *
     * The second element references the already-matched `bed` without a class annotation,
     * so the graph-modification layer cannot pre-narrow the arithmetic result — the raw
     * `Double` from `math()` reaches `setPropertyByKey`.
     */
    private fun buildIncrementAst(): TypedAst = TypedAst(
        types = types,
        metamodelPath = "",
        statements = listOf(
            TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        // Match: bed: BedAvailability {}
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = "match",
                                name = "bed",
                                className = className,
                                properties = emptyList()
                            )
                        ),
                        // Reference + update: bed { occupiedBeds = bed.occupiedBeds + 1 }
                        // className is intentionally null — this mirrors a referenced instance.
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = "match",
                                name = "bed",
                                className = null,
                                properties = listOf(
                                    TypedPatternPropertyAssignment(
                                        propertyName = propertyName,
                                        operator = "=",
                                        value = TypedBinaryExpression(
                                            evalType = 1, // int
                                            operator = "+",
                                            left = TypedMemberAccessExpression(
                                                evalType = 1, // int
                                                expression = TypedIdentifierExpression(
                                                    evalType = 2, // BedAvailability
                                                    name = "bed",
                                                    scope = 1
                                                ),
                                                member = propertyName,
                                                isNullChaining = false
                                            ),
                                            right = TypedIntLiteralExpression(
                                                evalType = 1,
                                                value = "1"
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
    )

    @Test
    fun `arithmetic increment on a referenced instance writes an Int without ClassCastException`() {
        registerTypes()

        val modelData = ModelData(
            metamodelPath = "",
            instances = listOf(
                ModelDataInstance(
                    name = "bed1",
                    className = className,
                    properties = mapOf(
                        propertyName to ModelDataPropertyValue.NumberValue(0.0)
                    )
                )
            ),
            links = emptyList()
        )

        val graph = MdeoModelGraph.create(modelData, metamodel)

        val engine = TransformationEngine(
            modelGraph = graph,
            ast = buildIncrementAst(),
            expressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
            statementExecutorRegistry = StatementExecutorRegistry.createDefaultRegistry()
        )

        val result = engine.execute()

        assertTrue(
            result is TransformationExecutionResult.Success,
            "Transformation should succeed but got: $result"
        )

        val g = graph.traversal()
        val bed = g.V().hasLabel(className).next()
        val value = bed.property<Any>(occupiedBedsKey).value()

        assertTrue(value is Int, "occupiedBeds must be stored as Int, got ${value?.let { it::class.simpleName }}")
        assertEquals(1, value, "occupiedBeds should have been incremented from 0 to 1")

        graph.close()
    }
}
