package com.mdeo.script.compiler

import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.metamodel.data.PropertyData
import com.mdeo.script.compiler.model.ScriptMetamodelTypeRegistrar
import com.mdeo.script.runtime.ExecutionEnvironment
import com.mdeo.script.runtime.SimpleScriptContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Regression test for a VerifyError caused by a missing unbox operation when
 * a lambda returns an int by reading a boxed Integer field from a metamodel instance.
 *
 * ## Bug description
 *
 * The lambda `(item: Item) => item.effort` is compiled to a JVM method with
 * signature `(Ljava/lang/Object;)I`, where:
 *   - local 0: `this` (ScriptProgram)
 *   - local 1: Item parameter `item`
 *
 * The field `effort` is stored as `java/lang/Integer` (boxed) in the metamodel
 * generated class.  ScriptMetamodelTypeRegistrar.getScriptPropertyDescriptor returns
 * `"Ljava/lang/Integer;"` for primitive type `"int"`, and FieldGetterPropertyDefinition
 * emits GETFIELD with that descriptor — leaving a boxed Integer on the operand stack.
 * The lambda return type is int (primitive), so ReturnStatementCompiler emits ireturn.
 *
 * The resulting bytecode sequence is:
 *   aload_1
 *   checkcast  <ItemClass>
 *   getfield   prop_0  Ljava/lang/Integer;   ← pushes boxed Integer onto stack
 *   ireturn                                  ← VerifyError: Integer not assignable to int
 *
 * The missing instruction between getfield and ireturn is:
 *   invokevirtual  java/lang/Integer.intValue()I
 *
 * ## Key difference from LambdaNestedCaptureBugTest / LambdaNestedCaptureObjectParamBugTest
 *
 * Those tests involve an incorrect slot index for a captured variable.  This test
 * targets a different root cause: a missing unbox after GETFIELD when the metamodel
 * stores an int property as a boxed wrapper type but the lambda's declared return type
 * is the corresponding primitive.
 *
 * ## Equivalent script
 *
 * ```
 * fun test(): int {
 *     var f = (item) => item.effort    // Item.effort is int; stored as Integer at JVM level
 *     return 0
 * }
 * ```
 *
 * The lambda `f` is defined but never invoked; the VerifyError fires at class-load time
 * when the JVM verifies all synthetic lambda methods in the generated ScriptProgram class.
 *
 * This test fails with a VerifyError before the bug is fixed, and passes (returning 0)
 * after the fix.
 */
class LambdaBoxedIntFieldReturnBugTest {

    private val metamodelPath = "/test-lambda-int-field.mm"
    private val classPackage = "${ScriptMetamodelTypeRegistrar.CLASS_PACKAGE}$metamodelPath"
    private val compiler = ScriptCompiler()
    private val testFilePath = "test://lambda-int-field.script"

    @Test
    fun `lambda returning int from boxed Integer metamodel field does not cause VerifyError`() {
        // Minimal metamodel: one class Item with a single int property `effort`.
        // The metamodel stores `effort` as Ljava/lang/Integer; in the generated
        // instance class (see ScriptMetamodelTypeRegistrar.getScriptPropertyDescriptor).
        val metamodelData = MetamodelData(
            path = metamodelPath,
            classes = listOf(
                ClassData(
                    name = "Item",
                    isAbstract = false,
                    properties = listOf(
                        PropertyData(
                            name = "effort",
                            primitiveType = "int",
                            multiplicity = MultiplicityData.single()
                        )
                    )
                )
            )
        )

        val ast = buildTypedAst {
            val intTypeIdx    = intType()   // 0 – builtin.int (non-nullable, maps to JVM int)
            val itemTypeIdx   = addType(ClassTypeRef(classPackage, "Item", false))   // 1
            val lambdaTypeIdx = lambdaType(
                ClassTypeRef("builtin", "int", false),
                "item" to ClassTypeRef(classPackage, "Item", false)
            )  // 2 – (Item) => int

            function(
                name = "test",
                returnType = intTypeIdx,
                body = listOf(
                    // var f = (item: Item) => item.effort
                    // Lambda is defined (triggering bytecode generation for the synthetic method)
                    // but never called, so no model instance is needed at runtime.
                    varDecl(
                        "f",
                        lambdaTypeIdx,
                        lambdaExpr(
                            parameters = listOf("item"),
                            body = listOf(
                                returnStmt(
                                    memberAccess(
                                        // `item` is the lambda parameter; lambda params scope is 4
                                        // (function body is scope 3, lambda params scope is 4).
                                        expression = identifier("item", itemTypeIdx, 4),
                                        member = "effort",
                                        resultTypeIndex = intTypeIdx
                                    )
                                )
                            ),
                            lambdaTypeIndex = lambdaTypeIdx
                        )
                    ),
                    returnStmt(intLiteral(0, intTypeIdx))
                )
            )
        }

        val input = CompilationInput(mapOf(testFilePath to ast))
        val program = compiler.compile(input, metamodelData)

        val env = ExecutionEnvironment(program)
        // null model is fine — the lambda is never called, so no model access occurs.
        val context = SimpleScriptContext(System.out, null)

        // Before the fix this throws:
        //   java.lang.VerifyError: Bad type on operand stack
        //     Location: …ScriptProgram.lambda$script$0(Ljava/lang/Object;)I @N: ireturn
        //     Reason: Type 'java/lang/Integer' … is not assignable to integer
        // After the fix the JVM accepts the class and the function returns 0.
        val result = env.invoke(testFilePath, "test", context)
        assertEquals(0, result)
    }
}
