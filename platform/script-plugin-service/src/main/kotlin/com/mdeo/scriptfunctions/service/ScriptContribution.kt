package com.mdeo.scriptfunctions.service

import com.mdeo.common.model.SessionType
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.ReturnTypeSerializer
import com.mdeo.expression.ast.types.ValueType
import com.mdeo.expression.ast.types.ValueTypeSerializer
import com.mdeo.expression.ast.types.VoidType
import com.mdeo.pluginservice.Contribution
import com.mdeo.pluginservice.ServedSession
import com.mdeo.scriptfunctions.protocol.ContributionNames
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
    val functions: Map<String, List<ScriptFunctionDeclaration>>,
    val records: Map<String, RecordType> = emptyMap(),
    val opaqueClasses: Map<String, OpaqueType> = emptyMap(),
    private val fieldDefaults: Map<String, Map<String, Any?>> = emptyMap()
) : Contribution {

    override val languageId: String = SCRIPT_LANGUAGE_ID

    /**
     * Every operation of every function, by operation name.
     */
    val operations: Map<String, ScriptFunctionOperation> =
        functions.values.flatten().associate { it.operation to it.implementation }

    override val sessions: Map<String, ServedSession> = mapOf(
        sessionName to ServedSession(
            SessionType(ScriptFunctionsProtocol.NAME, listOf(ScriptFunctionsProtocol.VERSION)),
            ScriptFunctionService(operations)
        )
    )

    override fun payload(): JsonObject = buildJsonObject {
        val types = PayloadTypes()
        put("type", SCRIPT_CONTRIBUTION_TYPE)
        putJsonObject("functions") {
            for ((name, overloads) in functions) {
                putJsonObject(name) {
                    putJsonObject("signatures") {
                        for (overload in overloads) {
                            put(overload.overload, overload.toJson(types))
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
                            val defaults = fieldDefaults[record.name].orEmpty()
                            for ((fieldName, fieldType) in record.fields) {
                                add(buildJsonObject {
                                    put("name", fieldName)
                                    put("type", typeJson.encodeToJsonElement(ValueTypeSerializer, fieldType))
                                    if (fieldName in defaults) {
                                        put("defaultValue", defaultValueJson(defaults[fieldName], fieldType, types))
                                    }
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
        // Written last, once every default value has added the types it refers to.
        put("types", types.toJson())
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
 * @property defaultValues The constant default values of the parameters that have one, by name
 * @property returnType The return type
 * @property generics Names of the generic type parameters the signature uses
 * @property operation The operation name sent over the session
 * @property readsModel Whether every call is sent the model the script runs on
 * @property implementation What answers a call
 */
class ScriptFunctionDeclaration internal constructor(
    val name: String,
    val overload: String,
    val parameters: List<Pair<String, ValueType>>,
    val defaultValues: Map<String, Any?>,
    val returnType: ReturnType,
    val generics: List<String>,
    val operation: String,
    val readsModel: Boolean,
    val implementation: ScriptFunctionOperation
) {
    internal fun toJson(types: PayloadTypes): JsonObject = buildJsonObject {
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
        }
        putJsonObject("implementation") {
            put("kind", "external")
            put("operation", operation)
            if (readsModel) put("model", "readonly")
        }
        if (defaultValues.isNotEmpty()) {
            putJsonObject("defaultValues") {
                for ((parameterName, type) in parameters) {
                    if (parameterName in defaultValues) {
                        put(parameterName, defaultValueJson(defaultValues[parameterName], type, types))
                    }
                }
            }
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

    private val functions = LinkedHashMap<String, MutableList<ScriptFunctionDeclaration>>()
    private val records = LinkedHashMap<String, RecordType>()
    private val opaqueClasses = LinkedHashMap<String, OpaqueType>()
    private val fieldDefaults = LinkedHashMap<String, Map<String, Any?>>()

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
        val builder = RecordBuilder().apply(init)
        val fields = builder.fields.toList()
        fieldDefaults[name] = builder.defaults.toMap()
        for ((fieldName, _) in fields) {
            require(fieldName != ContributionNames.RECORD_COPY_METHOD) {
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
        // Checked once everything is declared, so a record may name one declared after it.
        val rule = ContributionTypeRule(id, records.keys + opaqueClasses.keys)
        for (declaration in all) {
            val generics = declaration.generics.toSet()
            for ((parameterName, parameterType) in declaration.parameters) {
                rule.check(parameterType, "Parameter '$parameterName' of function '${declaration.name}'", isParameter = true, generics)
            }
            if (declaration.returnType !is VoidType) {
                rule.check(declaration.returnType, "The result of function '${declaration.name}'", isParameter = false, generics)
            }
        }
        for (record in records.values) {
            for ((fieldName, fieldType) in record.fields) {
                rule.check(fieldType, "Field '$fieldName' of record '${record.name}'", isParameter = false, emptySet())
            }
        }
        val duplicate = all.groupBy { it.operation }.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) {
            "Operation '${duplicate!!.key}' of contribution '$id' implements more than one signature"
        }
        return ScriptContribution(id, description, sessionName, functions, records, opaqueClasses, fieldDefaults)
    }
}

/**
 * Collects the fields of a record.
 */
class RecordBuilder internal constructor() {
    internal val fields = LinkedHashMap<String, ValueType>()
    internal val defaults = LinkedHashMap<String, Any?>()

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

    /**
     * Declares the next field, which a script's constructor call may leave out.
     *
     * @param name The field name, as scripts read it
     * @param type Its type
     * @param default The value it takes when left out: an `Int`, `Long`, `Float`, `Double`,
     *        `Boolean` or `String` matching [type], or `null` for a nullable type
     */
    fun field(name: String, type: ValueType, default: Any?) {
        requireDefaultFits(default, type, "Field '$name'")
        field(name, type)
        defaults[name] = default
    }
}

/**
 * Collects one signature of a contributed function.
 */
class ScriptFunctionBuilder internal constructor(private val name: String, private val overload: String) {
    private val parameters = mutableListOf<Pair<String, ValueType>>()
    private val defaultValues = LinkedHashMap<String, Any?>()
    private var returnType: ReturnType = VoidType()
    private val generics = mutableListOf<String>()
    private var implementation: ScriptFunctionOperation? = null

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
     * Declares the next parameter, which a call may leave out. The script evaluates the default,
     * so the operation is always sent every argument.
     *
     * @param name The parameter name
     * @param type Its type
     * @param default The value it takes when left out: an `Int`, `Long`, `Float`, `Double`,
     *        `Boolean` or `String` matching [type], or `null` for a nullable type
     */
    fun parameter(name: String, type: ValueType, default: Any?) {
        requireDefaultFits(default, type, "Parameter '$name' of function '${this.name}'")
        parameter(name, type)
        defaultValues[name] = default
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
        return ScriptFunctionDeclaration(name, overload, parameters.toList(), defaultValues.toMap(), returnType, generics.toList(), operation, readsModel, implementation)
    }
}
