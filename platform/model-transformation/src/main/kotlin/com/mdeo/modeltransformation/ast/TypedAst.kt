package com.mdeo.modeltransformation.ast

import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.ReturnTypeSerializer
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Root of the TypedAST containing all model transformation program information.
 *
 * This is the top-level data structure that represents a complete model transformation
 * program after type checking. It contains type information, metamodel reference,
 * and all transformation statements.
 *
 * Note: Metamodel class definitions are now fetched separately via MetamodelData
 * and passed to the TransformationEngine at execution time.
 *
 * @param types Array of all types used in the program. Generics are replaced by Any? due to
 *              type erasure. Types are indexed by typeIndex in expressions and statements.
 * @param metamodelPath Absolute path of the imported metamodel file that this transformation operates on.
 * @param statements All top-level transformation statements in the program.
 * @param signature The header declarations (`in`/`out`) of the file, named after the file's base
 *   name. Every file is callable; a file without declarations has an empty parameter list or no
 *   signature, which the engine treats the same.
 * @param callees The typed ASTs of every transformation this one calls, directly or through its
 *   callees, by absolute file path. Only the root of a bundle has callees; the engine of a callee
 *   looks its own callees up in the root's map.
 */
@Serializable
data class TypedAst(
    val types: List<@Serializable(with = ReturnTypeSerializer::class) ReturnType>,
    val metamodelPath: String,
    val statements: List<@Contextual TypedTransformationStatement>,
    val signature: TypedTransformationSignature? = null,
    val callees: Map<String, TypedAst> = emptyMap()
)
