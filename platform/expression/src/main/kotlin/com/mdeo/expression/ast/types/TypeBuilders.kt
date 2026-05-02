package com.mdeo.expression.ast.types

/**
 * DSL helper functions for constructing ValueType and ReturnType instances.
 *
 * These functions provide a fluent API for creating type definitions
 * in the stdlib registry without manual JSON-like object construction.
 */

/**
 * Creates a void return type.
 *
 * @return A VoidType instance.
 */
fun voidType(): VoidType = VoidType()

/**
 * Creates a non-nullable class type reference.
 *
 * @param pkg The package of the type (e.g., "builtin", "class/path/to/file").
 * @param typeName The simple type name (e.g., "int", "string", "ClassName").
 * @return A ClassTypeRef with isNullable = false.
 */
fun classType(pkg: String, typeName: String): ClassTypeRef = 
    ClassTypeRef(pkg, typeName, false, null)

/**
 * Creates a non-nullable builtin class type reference.
 *
 * @param typeName The simple type name (e.g., "int", "string").
 * @return A ClassTypeRef with package="builtin" and isNullable = false.
 */
fun builtinType(typeName: String): ClassTypeRef = 
    ClassTypeRef("builtin", typeName, false, null)

/**
 * Creates a nullable class type reference.
 *
 * @param pkg The package of the type (e.g., "builtin", "class/path/to/file").
 * @param typeName The simple type name (e.g., "int", "string", "ClassName").
 * @return A ClassTypeRef with isNullable = true.
 */
fun nullableClassType(pkg: String, typeName: String): ClassTypeRef = 
    ClassTypeRef(pkg, typeName, true, null)

/**
 * Creates a nullable builtin class type reference.
 *
 * @param typeName The simple type name (e.g., "int", "string").
 * @return A ClassTypeRef with package="builtin" and isNullable = true.
 */
fun nullableBuiltinType(typeName: String): ClassTypeRef = 
    ClassTypeRef("builtin", typeName, true, null)

/**
 * Creates a class type reference with generic type arguments.
 *
 * @param pkg The package of the type (e.g., "builtin", "class/path/to/file").
 * @param typeName The simple type name (e.g., "List", "Map").
 * @param isNullable Whether the type is nullable.
 * @param typeArgs The type arguments as a map (e.g., mapOf("T" to builtinType("string"))).
 * @return A ClassTypeRef with type arguments.
 */
fun genericClassType(
    pkg: String,
    typeName: String,
    isNullable: Boolean = false,
    typeArgs: Map<String, ValueType>
): ClassTypeRef = ClassTypeRef(pkg, typeName, isNullable, typeArgs)

/**
 * Creates a lambda type.
 *
 * @param returnType The return type of the lambda.
 * @param parameters The parameters of the lambda.
 * @param isNullable Whether the lambda type is nullable.
 * @return A LambdaType instance.
 */
fun lambdaType(
    returnType: ReturnType,
    parameters: List<Parameter> = emptyList(),
    isNullable: Boolean = false
): LambdaType = LambdaType(returnType, parameters, isNullable)

/**
 * Creates a lambda type with a simple parameter list.
 *
 * @param returnType The return type of the lambda.
 * @param isNullable Whether the lambda type is nullable.
 * @param params Pairs of (name, type) for the parameters.
 * @return A LambdaType instance.
 */
fun lambdaType(
    returnType: ReturnType,
    isNullable: Boolean = false,
    vararg params: Pair<String, ValueType>
): LambdaType = LambdaType(
    returnType,
    params.map { (name, type) -> Parameter(name, type) },
    isNullable
)

/**
 * Object containing common type constants for use in stdlib definitions.
 *
 * This avoids repeatedly constructing common types like int, string, boolean, etc.
 */
object BuiltinTypes {
    val INT = builtinType("int")
    val LONG = builtinType("long")
    val FLOAT = builtinType("float")
    val DOUBLE = builtinType("double")
    val BOOLEAN = builtinType("boolean")
    val STRING = builtinType("string")
    val ANY = builtinType("Any")
    val NULLABLE_INT = nullableBuiltinType("int")
    val NULLABLE_LONG = nullableBuiltinType("long")
    val NULLABLE_FLOAT = nullableBuiltinType("float")
    val NULLABLE_DOUBLE = nullableBuiltinType("double")
    val NULLABLE_BOOLEAN = nullableBuiltinType("boolean")
    val NULLABLE_STRING = nullableBuiltinType("string")
    val NULLABLE_ANY = nullableBuiltinType("Any")
    val LIST = genericClassType("builtin", "List", typeArgs = mapOf("T" to NULLABLE_ANY))
    val SET = genericClassType("builtin", "Set", typeArgs = mapOf("T" to NULLABLE_ANY))
    val BAG = genericClassType("builtin", "Bag", typeArgs = mapOf("T" to NULLABLE_ANY))
    val ORDERED_SET = genericClassType("builtin", "OrderedSet", typeArgs = mapOf("T" to NULLABLE_ANY))
    val COLLECTION = builtinType("Collection")
    val READONLY_COLLECTION = builtinType("ReadonlyCollection")
    val READONLY_LIST = builtinType("ReadonlyList")
    val READONLY_SET = builtinType("ReadonlySet")
    val READONLY_BAG = builtinType("ReadonlyBag")
    val READONLY_ORDERED_COLLECTION = builtinType("ReadonlyOrderedCollection")
    val READONLY_ORDERED_SET = builtinType("ReadonlyOrderedSet")
    val ORDERED_COLLECTION = builtinType("OrderedCollection")
    val MAP = genericClassType("builtin", "Map", typeArgs = mapOf("K" to NULLABLE_ANY, "V" to NULLABLE_ANY))
    val READONLY_MAP = genericClassType("builtin", "ReadonlyMap", typeArgs = mapOf("K" to NULLABLE_ANY, "V" to NULLABLE_ANY))
    val VOID = voidType()
    
    /**
     * Creates a predicate lambda type: (T) -> boolean
     *
     * @param paramType The type of the predicate parameter.
     * @return A LambdaType for a predicate function.
     */
    fun predicate(paramType: ValueType = NULLABLE_ANY): LambdaType =
        lambdaType(BOOLEAN, false, "it" to paramType)
    
    /**
     * Creates a consumer lambda type: (T) -> void
     *
     * @param paramType The type of the consumer parameter.
     * @return A LambdaType for a consumer function.
     */
    fun consumer(paramType: ValueType = NULLABLE_ANY): LambdaType =
        lambdaType(VOID, false, "it" to paramType)
    
    /**
     * Creates a function lambda type: (T) -> R
     *
     * @param paramType The type of the function parameter.
     * @param returnType The return type of the function.
     * @return A LambdaType for a function.
     */
    fun function(paramType: ValueType = NULLABLE_ANY, returnType: ReturnType = NULLABLE_ANY): LambdaType =
        lambdaType(returnType, false, "it" to paramType)
}
