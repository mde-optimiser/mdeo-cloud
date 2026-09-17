package com.mdeo.script.ast

import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.ReturnTypeSerializer
import kotlinx.serialization.Serializable

/**
 * Root of the TypedAST containing all script program information.
 *
 * This is the top-level data structure that represents a complete script
 * program after type checking. It contains type information, imports,
 * and all function definitions.
 *
 * Note: Metamodel class definitions are now fetched separately via MetamodelData
 * and passed to the execution engine at runtime.
 *
 * @param types Array of all types used in the program. Generics are replaced by Any? due to
 *              type erasure. Types are indexed by typeIndex in expressions.
 * @param metamodelPath Optional absolute path of the imported metamodel file.
 *        When present, the script has access to metamodel types and the `model` global.
 * @param imports All imports in the program.
 * @param functions All top-level functions in the program.
 * @param records All records the program declares.
 */
@Serializable
data class TypedAst(
    val types: List<@Serializable(with = ReturnTypeSerializer::class) ReturnType>,
    val metamodelPath: String? = null,
    val imports: List<TypedImport>,
    val functions: List<TypedFunction>,
    val records: List<TypedRecord> = emptyList()
)
