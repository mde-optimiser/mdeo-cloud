package com.mdeo.scriptfunctions.service

import com.mdeo.common.model.SessionType
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.LambdaType
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.ReturnTypeSerializer
import com.mdeo.expression.ast.types.ValueType
import com.mdeo.expression.ast.types.ValueTypeSerializer
import com.mdeo.expression.ast.types.VoidType
import com.mdeo.pluginservice.Contribution
import com.mdeo.pluginservice.ServedSession
import com.mdeo.scriptfunctions.protocol.ScriptFunctionsProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The id of the script language, which script contributions extend.
 */
const val SCRIPT_LANGUAGE_ID = "script"

/**
 * The `type` discriminator of a script contribution payload.
 */
const val SCRIPT_CONTRIBUTION_TYPE = "script-language-contribution"

/**
 * The session name a script contribution declares unless told otherwise.
 */
const val DEFAULT_SCRIPT_FUNCTIONS_SESSION = "functions"

/**
 * A contribution of functions to the script language, all implemented by this service.
 *
 * Build one with [scriptContribution]. Every function becomes an external implementation in the
 * payload, and its operation is registered with the contribution's `script-functions` session, so
 * what the manifest declares and what the service answers come from the same declaration.
 *
 * @property id The contribution id; scripts' calls reach this service as `contrib:<id>`
 * @property description Shown in the plugin details view
 * @property sessionName The name of the `script-functions` session
 * @property functions The contributed functions, by name
 * @property records The records the contribution defines, by name
 * @property opaqueClasses The opaque classes the contribution defines, by name
 */
class ScriptContribution internal constructor(
    override val id: String,
    override val description: String,
    val sessionName: String,
    private val sessionDescription: String?,
    val functions: Map<String, List<ScriptFunctionDeclaration>>,
    val records: Map<String, RecordType> = emptyMap(),
    val opaqueClasses: Map<String, OpaqueType> = emptyMap()
) : Contribution {

    override val languageId: String = SCRIPT_LANGUAGE_ID

    /**
     * Every operation of every function, by operation name.
     */
    val operations: Map<String, ScriptFunctionOperation> =
        functions.values.flatten().associate { it.operation to it.implementation }

    override val sessions: Map<String, ServedSession> = mapOf(
        sessionName to ServedSession(
            SessionType(ScriptFunctionsProtocol.NAME, listOf(ScriptFunctionsProtocol.VERSION), sessionDescription),
            ScriptFunctionService(operations)
        )
    )

    override fun payload(): JsonObject = buildJsonObject {
        put("type", SCRIPT_CONTRIBUTION_TYPE)
        putJsonArray("types") {}
        putJsonObject("functions") {
            for ((name, overloads) in functions) {
                putJsonObject(name) {
                    putJsonObject("signatures") {
                        for (overload in overloads) {
                            put(overload.overload, overload.toJson())
                        }
                    }
                }
            }
        }
        putJsonObject("expressions") {}
        if (records.isNotEmpty() || opaqueClasses.isNotEmpty()) {
            putJsonObject("classes") {
                for (record in records.values) {
                    putJsonObject(record.name) {
                        put("kind", "record")
                        putJsonArray("fields") {
                            for ((fieldName, fieldType) in record.fields) {
                                add(buildJsonObject {
                                    put("name", fieldName)
                                    put("type", typeJson.encodeToJsonElement(ValueTypeSerializer, fieldType))
                                })
                            }
                        }
                    }
                }
                for (opaque in opaqueClasses.values) {
                    putJsonObject(opaque.name) { put("kind", "opaque") }
                }
            }
        }
    }

    private companion object {
        val typeJson = Json { explicitNulls = false }
    }
}

/**
 * One signature of one contributed function, and the operation that implements it.
 *
 * @property name The function name scripts call
 * @property overload The signature key; empty for a function with a single signature
 * @property parameters The parameters, by name and type
 * @property returnType The return type
 * @property generics Names of the generic type parameters the signature uses
 * @property isVarArgs Whether the last parameter takes any number of arguments
 * @property operation The operation name sent over the session
 * @property readsModel Whether every call is sent the model the script runs on
 * @property implementation What answers a call
 */
class ScriptFunctionDeclaration internal constructor(
    val name: String,
    val overload: String,
    val parameters: List<Pair<String, ValueType>>,
    val returnType: ReturnType,
    val generics: List<String>,
    val isVarArgs: Boolean,
    val operation: String,
    val readsModel: Boolean,
    val implementation: ScriptFunctionOperation
) {
    internal fun toJson(): JsonObject = buildJsonObject {
        putJsonObject("signature") {
            putJsonArray("parameters") {
                for ((parameterName, type) in parameters) {
                    add(buildJsonObject {
                        put("name", parameterName)
                        put("type", typeJson.encodeToJsonElement(ValueTypeSerializer, type))
                    })
                }
            }
            put("returnType", typeJson.encodeToJsonElement(ReturnTypeSerializer, returnType))
            if (generics.isNotEmpty()) putJsonArray("generics") { generics.forEach { add(JsonPrimitive(it)) } }
            if (isVarArgs) put("isVarArgs", true)
        }
        putJsonObject("implementation") {
            put("kind", "external")
            put("operation", operation)
            if (readsModel) put("model", "readonly")
        }
    }

    private companion object {
        val typeJson = Json { explicitNulls = false }
    }
}

/**
 * Declares a contribution of functions to the script language.
 *
 * ```kotlin
 * val routing = scriptContribution("routing") {
 *     description = "Route planning"
 *     function("shortestTour") {
 *         parameter("stops", genericClassType("builtin", "ReadonlyList", typeArgs = mapOf("T" to BuiltinTypes.STRING)))
 *         returns(genericClassType("builtin", "List", typeArgs = mapOf("T" to BuiltinTypes.STRING)))
 *         implementation { call -> solve(call.argument<List<String>>(0)) }
 *     }
 * }
 * ```
 *
 * @param id The contribution id, unique within a project
 * @param init Declares the functions
 * @return The contribution, to list in a [com.mdeo.pluginservice.PluginDefinition]
 */
fun scriptContribution(id: String, init: ScriptContributionBuilder.() -> Unit): ScriptContribution =
    ScriptContributionBuilder(id).apply(init).build()

/**
 * Collects the functions of a [scriptContribution].
 */
class ScriptContributionBuilder internal constructor(private val id: String) {
    /**
     * What the contribution provides, shown in the plugin details view.
     */
    var description: String = ""

    /**
     * The name of the `script-functions` session calls arrive on.
     */
    var sessionName: String = DEFAULT_SCRIPT_FUNCTIONS_SESSION

    /**
     * What the session is for, shown in the plugin details view.
     */
    var sessionDescription: String? = null

    private val functions = LinkedHashMap<String, MutableList<ScriptFunctionDeclaration>>()
    private val records = LinkedHashMap<String, RecordType>()
    private val opaqueClasses = LinkedHashMap<String, OpaqueType>()

    /**
     * The package this contribution's classes are referred to by.
     */
    private val classPackage = "$CONTRIBUTED_CLASS_PACKAGE/$id"

    /**
     * Declares a record: a value with named fields, sent whole.
     *
     * Scripts construct records, read and assign their fields and copy them with `with`, like the
     * records they declare themselves. A field holds a scalar, a string, a model instance or enum
     * value, a record of this contribution declared before it, or a collection of those. No field
     * may be named `with`.
     *
     * ```kotlin
     * val point = record("Point") {
     *     field("x", BuiltinTypes.DOUBLE)
     *     field("label", BuiltinTypes.STRING)
     * }
     * ```
     *
     * @param name The record's name
     * @param init Declares the fields, in order
     * @return The record, whose [RecordType.type] signatures use
     */
    fun record(name: String, init: RecordBuilder.() -> Unit): RecordType {
        requireNewClassName(name)
        val fields = RecordBuilder().apply(init).fields.toList()
        for ((fieldName, fieldType) in fields) {
            require(isRecordFieldType(fieldType)) {
                "Field '$fieldName' of record '$name' has a type a record cannot hold. Use scalars, strings, " +
                        "model instances, enum values, records of the same contribution, or collections of those."
            }
            require(fieldName != RECORD_COPY_METHOD) {
                "Field '$fieldName' of record '$name' has the name of the method that copies a record"
            }
        }
        return RecordType(name, ClassTypeRef(classPackage, name, false), fields).also { records[name] = it }
    }

    /**
     * Declares an opaque class: a handle scripts hold to state that stays on this service.
     *
     * @param name The class's name
     * @return The class, whose [OpaqueType.type] signatures use
     */
    fun opaque(name: String): OpaqueType {
        requireNewClassName(name)
        return OpaqueType(name, ClassTypeRef(classPackage, name, false)).also { opaqueClasses[name] = it }
    }

    private fun requireNewClassName(name: String) {
        require(name !in records && name !in opaqueClasses) { "Contribution '$id' declares class '$name' twice" }
    }

    private fun isRecordFieldType(type: ValueType): Boolean {
        if (type !is ClassTypeRef) return false
        return when {
            type.`package` == "builtin" -> when (type.type) {
                in SCALAR_TYPES -> true
                in COLLECTION_TYPES -> type.typeArgs.orEmpty().values.all(::isRecordFieldType)
                else -> false
            }
            type.`package` == classPackage -> type.type in records
            type.`package`.startsWith("$CONTRIBUTED_CLASS_PACKAGE/") -> false
            else -> type.`package`.startsWith("class/") || type.`package`.startsWith("enum/")
        }
    }

    /**
     * Declares one signature of a function. Declare the same name again with another [overload]
     * key to add an overload.
     *
     * @param name The function name scripts call
     * @param overload The signature key; leave empty for a function with a single signature
     * @param init Declares the parameters, return type and implementation
     */
    fun function(name: String, overload: String = "", init: ScriptFunctionBuilder.() -> Unit) {
        val declaration = ScriptFunctionBuilder(name, overload).apply(init).build()
        val overloads = functions.getOrPut(name) { mutableListOf() }
        require(overloads.none { it.overload == overload }) {
            "Function '$name' of contribution '$id' declares signature '$overload' twice"
        }
        overloads += declaration
    }

    internal fun build(): ScriptContribution {
        val all = functions.values.flatten()
        for (declaration in all) {
            val types = declaration.parameters.map { it.second } + declaration.returnType
            types.forEach { checkClassReference(it, "Function '${declaration.name}'") }
        }
        val duplicate = all.groupBy { it.operation }.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) {
            "Operation '${duplicate!!.key}' of contribution '$id' implements more than one signature"
        }
        return ScriptContribution(id, description, sessionName, sessionDescription, functions, records, opaqueClasses)
    }

    private fun checkClassReference(type: com.mdeo.expression.ast.types.ReturnType, where: String) {
        if (type !is ClassTypeRef) return
        if (type.`package`.startsWith("$CONTRIBUTED_CLASS_PACKAGE/")) {
            require(type.`package` == classPackage && (type.type in records || type.type in opaqueClasses)) {
                "$where of contribution '$id' refers to class '${type.type}' of '${type.`package`}', which the contribution does not define"
            }
        }
        type.typeArgs.orEmpty().values.forEach { checkClassReference(it, where) }
    }

    private companion object {
        val SCALAR_TYPES = setOf("int", "long", "float", "double", "boolean", "string")
        val COLLECTION_TYPES = setOf(
            "Collection", "OrderedCollection", "List", "Set", "OrderedSet", "Bag", "Map",
            "ReadonlyCollection", "ReadonlyOrderedCollection", "ReadonlyList", "ReadonlySet",
            "ReadonlyOrderedSet", "ReadonlyBag", "ReadonlyMap"
        )

        /**
         * The method every record has that copies it, which no field may shadow.
         */
        const val RECORD_COPY_METHOD = "with"
    }
}

/**
 * Collects the fields of a record.
 */
class RecordBuilder internal constructor() {
    internal val fields = LinkedHashMap<String, ValueType>()

    /**
     * Declares the next field.
     *
     * @param name The field name, as scripts read it
     * @param type Its type
     */
    fun field(name: String, type: ValueType) {
        require(name !in fields) { "Field '$name' is declared twice" }
        fields[name] = type
    }
}

/**
 * Collects one signature of a contributed function.
 */
class ScriptFunctionBuilder internal constructor(private val name: String, private val overload: String) {
    private val parameters = mutableListOf<Pair<String, ValueType>>()
    private var returnType: ReturnType = VoidType()
    private val generics = mutableListOf<String>()
    private var implementation: ScriptFunctionOperation? = null

    /**
     * Whether the last parameter takes any number of arguments.
     */
    var isVarArgs: Boolean = false

    /**
     * The operation name sent over the session. Defaults to the function name, followed by
     * `/<overload>` for a named overload.
     */
    var operation: String = if (overload.isEmpty()) name else "$name/$overload"

    /**
     * Whether the operation reads the model the script runs on, through [ScriptFunctionCall.model].
     * The model is uploaded once and reused until the script works on a different one. A function
     * that is passed model instances gets the model whether or not this is set.
     */
    var readsModel: Boolean = false

    /**
     * Declares the next parameter.
     *
     * @param name The parameter name
     * @param type Its type. Every argument is readonly to the operation, whatever its type.
     */
    fun parameter(name: String, type: ValueType) {
        parameters += name to type
    }

    /**
     * Declares the return type. Functions return `void` unless told otherwise.
     *
     * @param type The return type
     */
    fun returns(type: ReturnType) {
        returnType = type
    }

    /**
     * Declares generic type parameters, to be referenced with `GenericTypeRef`.
     *
     * @param names The type parameter names
     */
    fun generics(vararg names: String) {
        generics += names
    }

    /**
     * Declares what answers a call.
     *
     * @param operation The implementation
     */
    fun implementation(operation: ScriptFunctionOperation) {
        implementation = operation
    }

    internal fun build(): ScriptFunctionDeclaration {
        val label = if (overload.isEmpty()) "Function '$name'" else "Signature '$overload' of function '$name'"
        val implementation = requireNotNull(implementation) { "$label has no implementation" }
        // A lambda is code inside the execution process, which cannot be sent.
        parameters.firstOrNull { containsLambda(it.second) }?.let {
            throw IllegalArgumentException("$label takes a lambda in parameter '${it.first}', which cannot be sent to a service")
        }
        require(!containsLambda(returnType)) { "$label returns a lambda, which cannot be sent from a service" }
        return ScriptFunctionDeclaration(name, overload, parameters.toList(), returnType, generics.toList(), isVarArgs, operation, readsModel, implementation)
    }

    private fun containsLambda(type: ReturnType): Boolean = when (type) {
        is LambdaType -> true
        is ClassTypeRef -> type.typeArgs.orEmpty().values.any(::containsLambda)
        else -> false
    }
}
