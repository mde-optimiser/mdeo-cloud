package com.mdeo.optimizer.rulegen

import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.modeltransformation.ast.expressions.TypedExpressionSerializer
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElementSerializer
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatementSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Generated operators reach the workers as JSON: the execution service encodes them, each worker
 * decodes them. Every operator for MDEOptimiser's example metamodels must come back unchanged
 * with the codecs both sides use.
 */
class GeneratedOperatorSerializationTest {

    private val module = SerializersModule {
        contextual(TypedExpression::class, TypedExpressionSerializer)
        contextual(TypedTransformationStatement::class, TypedTransformationStatementSerializer)
        contextual(TypedPatternElement::class, TypedPatternElementSerializer)
    }

    /** As in `OptimizerExecutionService`, which encodes. */
    private val serviceJson = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true; serializersModule = module }

    /** As in `WorkerSubprocessMain`, which decodes. */
    private val workerJson = Json { ignoreUnknownKeys = true; isLenient = true; serializersModule = module }

    @Test
    fun `every generated operator survives the trip to the workers`() {
        for ((name, metamodel) in ExampleMetamodels.all) {
            val mutations = MutationRuleGenerator.generate(metamodel, metamodel.classes.map { spec("mutate ${it.name}") }).mutations
            for (mutation in mutations) {
                val json = serviceJson.encodeToString(TypedAst.serializer(), mutation.typedAst)
                val decoded = workerJson.decodeFromString(TypedAst.serializer(), json)
                assertEquals(mutation.typedAst, decoded, "$name: ${mutation.name}")
            }
        }
    }
}
