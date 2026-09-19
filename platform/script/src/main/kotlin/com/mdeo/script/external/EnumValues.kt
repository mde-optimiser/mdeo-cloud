package com.mdeo.script.external

import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.metamodel.Metamodel
import com.mdeo.script.compiler.model.ScriptMetamodelTypeRegistrar
import com.mdeo.scriptfunctions.protocol.WireValue
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * The mapping between a script's enum values and their wire form, the enum and entry name.
 *
 * A script's enum values are instances of the classes its metamodel generates, one class per enum,
 * and each entry is a single instance held by the enum's container class. Decoding returns that
 * very instance, so an enum value that comes back from a service is equal to the script's own.
 *
 * Neither direction needs the model: a script may run on a metamodel without one. Which enum a
 * value is of is known from the enums the contribution's signatures and records declare, and
 * otherwise from the model's metamodel, when there is a model.
 *
 * @param declaredTypes Every type the contribution's signatures and record fields declare
 */
internal class EnumValues(declaredTypes: Collection<ReturnType>) {

    /**
     * The declared enums, by the binary name of their value class.
     */
    private val enumsByValueClass: Map<String, String> = buildMap {
        declaredTypes.forEach { collectEnums(it, this) }
    }

    /**
     * Encodes an enum value.
     *
     * @param value Any value
     * @param metamodel The metamodel of the model the script runs on, if it runs on one
     * @return The enum value on the wire, or null when [value] is not an enum value
     */
    fun encode(value: Any, metamodel: Metamodel?): WireValue.EnumValue? {
        val className = value.javaClass.name
        val enumName = enumsByValueClass[className]
            ?: metamodel?.metadata?.enumValueClassNames?.entries?.firstOrNull { it.value == className }?.key
            ?: return null
        return WireValue.EnumValue(enumName, ENTRY_GETTERS.get(value.javaClass).invoke(value) as String)
    }

    /**
     * Decodes an enum value.
     *
     * @param value The enum value on the wire
     * @param classLoader The class loader of the running program, which sees the metamodel's classes
     * @return The script's value for the entry, or null when the metamodel declares no such entry
     */
    fun decode(value: WireValue.EnumValue, classLoader: ClassLoader): Any? {
        val container = try {
            classLoader.loadClass(binaryName(Metamodel.getEnumContainerClassName(value.enumName)))
        } catch (_: ClassNotFoundException) {
            return null
        }
        val valueClass = binaryName(Metamodel.getEnumValueClassName(value.enumName))
        // The container also holds its own singleton, so the field must hold an entry.
        val field = container.fields.firstOrNull {
            it.name == value.entry && Modifier.isStatic(it.modifiers) && it.type.name == valueClass
        } ?: return null
        return field.get(null)
    }

    companion object {
        private const val ENUM_PACKAGE_PREFIX = "${ScriptMetamodelTypeRegistrar.ENUM_PACKAGE}/"

        private val ENTRY_GETTERS = object : ClassValue<Method>() {
            override fun computeValue(type: Class<*>): Method = type.getMethod("getEntry")
        }

        /**
         * Returns the entry name of an enum value.
         *
         * @param value Any value
         * @return The entry name, or null when [value] is not an enum value
         */
        fun entryOf(value: Any): String? =
            runCatching { ENTRY_GETTERS.get(value.javaClass).invoke(value) as String }.getOrNull()

        private fun binaryName(internalName: String): String = internalName.replace('/', '.')

        private fun collectEnums(type: ReturnType, into: MutableMap<String, String>) {
            if (type !is ClassTypeRef) return
            if (type.`package`.startsWith(ENUM_PACKAGE_PREFIX)) {
                into[binaryName(Metamodel.getEnumValueClassName(type.type))] = type.type
            }
            type.typeArgs?.values?.forEach { collectEnums(it, into) }
        }
    }
}
