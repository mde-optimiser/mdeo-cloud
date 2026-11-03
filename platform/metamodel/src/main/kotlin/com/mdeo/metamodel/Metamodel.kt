package com.mdeo.metamodel

import com.mdeo.metamodel.data.*
import java.lang.reflect.Constructor

/**
 * A compiled metamodel that holds generated JVM classes for instance classes,
 * enum value/container classes, and class container classes.
 *
 * The [compile] factory method generates bytecode via ASM for every class and enum
 * in the supplied [MetamodelData], loads them through a [MetamodelClassLoader], and
 * builds [MetamodelMetadata] mapping property/association names to `prop_X` field indices.
 *
 * Instance classes extend [ModelInstance] and store all property + association values
 * as `prop_0`, `prop_1`, … fields with proper JVM types.
 *
 * @property data The raw metamodel data this instance was compiled from.
 * @property metadata The compiled metadata (field mappings, class hierarchy, etc.).
 * @property classLoader The class loader that holds all generated JVM classes.
 */
class Metamodel internal constructor(
    val data: MetamodelData,
    val metadata: MetamodelMetadata,
    val classLoader: MetamodelClassLoader,
    private val instanceClasses: Map<String, Class<*>>,
    private val enumContainerClasses: Map<String, Class<*>>,
    private val enumValueClasses: Map<String, Class<*>>,
    private val classContainerClasses: Map<String, Class<*>>
) {
    /**
     * Absolute file-system path of this metamodel, as declared in [MetamodelData.path]. 
     */
    val path: String get() = data.path

    /**
     * Reverse lookup from generated JVM class to metamodel class name. 
     */
    private val classNameByClass: Map<Class<*>, String> = instanceClasses.entries.associate { (name, clazz) -> clazz to name }

    /**
     * Cached no-arg constructors for each generated instance class.
     *
     * Built once at [Metamodel] construction time; [isAccessible] is set to `true` so that
     * [createInstance] bypasses the per-call access check in [Constructor.newInstance],
     * eliminating [java.lang.reflect.AccessibleObject.slowVerifyAccess] overhead.
     */
    private val cachedConstructors: Map<String, Constructor<*>> = instanceClasses.mapValues { (_, clazz) ->
        clazz.getDeclaredConstructor().also { it.isAccessible = true }
    }
    
    companion object {
        internal const val INSTANCE_PACKAGE = "com/mdeo/metamodel/generated/instance"
        internal const val CONTAINER_PACKAGE = "com/mdeo/metamodel/generated/classcontainer"
        internal const val ENUM_VALUE_PACKAGE = "com/mdeo/metamodel/generated/enum"

        /**
         * Thread-local model provider used by generated class container `all()` methods.
         *
         * Set via [Model.withModelProvider] before executing model-aware code.
         */
        @JvmStatic
        val currentModelProvider: ThreadLocal<ModelProvider?> = ThreadLocal.withInitial { null }

        /**
         * Compiles a [MetamodelData] description into a fully initialized [Metamodel] with
         * generated JVM classes for all metamodel classes and enums.
         *
         * @param data The metamodel data to compile.
         * @param parentClassLoader The parent class loader for the generated [MetamodelClassLoader].
         * @return The compiled [Metamodel].
         */
        fun compile(data: MetamodelData, parentClassLoader: ClassLoader = Metamodel::class.java.classLoader): Metamodel {
            return MetamodelCompiler(data).compile(parentClassLoader)
        }

        /**
         * Returns the JVM internal name for the generated instance class of the given metamodel class.
         *
         * @param className The metamodel class name.
         * @return The JVM internal name for the instance class.
         */
        fun getInstanceClassName(className: String): String {
            return "$INSTANCE_PACKAGE/${className}Instance"
        }

        /**
         * Returns the JVM internal name for the generated type interface of the given metamodel class.
         *
         * A class may extend several classes, but its instance class can extend only one of theirs.
         * The type interface is implemented by the instance class of the class and of every class
         * inheriting from it, however it inherits, so code that needs the type of an instance —
         * a cast, an `instanceof`, a property read — uses the interface instead of the class.
         *
         * @param className The metamodel class name.
         * @return The JVM internal name for the type interface.
         */
        fun getInstanceInterfaceName(className: String): String {
            return "$INSTANCE_PACKAGE/${className}Type"
        }

        /**
         * Returns the name of the method that reads the `prop_X` field with the given index. Every
         * instance class implements it for each of its fields, and the type interface of the class
         * declaring the property declares it.
         *
         * @param fieldIndex The field index X.
         * @return The getter name.
         */
        fun getFieldGetterName(fieldIndex: Int): String {
            return "get_prop_$fieldIndex"
        }

        /**
         * Returns the JVM internal name for the generated class container class of the given metamodel class.
         *
         * @param className The metamodel class name.
         * @return The JVM internal name for the class container class.
         */
        fun getClassContainerClassName(className: String): String {
            return "$CONTAINER_PACKAGE/${className}ClassContainer"
        }

        /**
         * Returns the JVM internal name for the generated enum value class of the given enum.
         *
         * @param enumName The metamodel enum name.
         * @return The JVM internal name for the enum value class.
         */
        fun getEnumValueClassName(enumName: String): String {
            return "$ENUM_VALUE_PACKAGE/${enumName}EnumValue"
        }

        /**
         * Returns the JVM internal name for the generated enum container class of the given enum.
         *
         * @param enumName The metamodel enum name.
         * @return The JVM internal name for the enum container class.
         */
        fun getEnumContainerClassName(enumName: String): String {
            return "$ENUM_VALUE_PACKAGE/${enumName}Enum"
        }

        /**
         * Converts a JVM internal name (slash-separated) to a binary name (dot-separated).
         *
         * @param internalName The JVM internal name.
         * @return The binary name suitable for [ClassLoader.loadClass].
         */
        internal fun toBinaryName(internalName: String): String {
            return internalName.replace('/', '.')
        }
    }

    /**
     * Functional interface for providing model instances to generated class container `all()` methods.
     *
     * Installed via [Model.withModelProvider] on a per-thread basis.
     */
    fun interface ModelProvider {
        /**
         * Returns all instances of the given class (including subtypes).
         *
         * @param className The metamodel class name.
         */
        fun getAllInstances(className: String): List<ModelInstance>
    }

    /**
     * Returns the generated JVM class for the given metamodel class name.
     *
     * @param className The metamodel class name.
     * @throws IllegalStateException if no class was generated for [className].
     */
    fun getInstanceClass(className: String): Class<*> {
        return instanceClasses[className] ?: error("No generated class for metamodel class '$className'")
    }

    /**
     * Returns the generated enum container class for the given enum name.
     *
     * @param enumName The metamodel enum name.
     * @throws IllegalStateException if no container class was generated for [enumName].
     */
    fun getEnumContainerClass(enumName: String): Class<*> {
        return enumContainerClasses[enumName] ?: error("No generated class for enum '$enumName'")
    }

    /**
     * Returns the generated enum value class for the given enum name.
     *
     * @param enumName The metamodel enum name.
     * @throws IllegalStateException if no value class was generated for [enumName].
     */
    fun getEnumValueClass(enumName: String): Class<*> {
        return enumValueClasses[enumName] ?: error("No generated enum value class for enum '$enumName'")
    }

    /**
     * Resolves a specific enum entry to its singleton JVM object.
     *
     * @param enumName The metamodel enum name.
     * @param entryName The enum entry name.
     * @return The singleton enum value object from the generated enum container class.
     * @throws IllegalStateException if the entry is not found.
     */
    fun resolveEnumValue(enumName: String, entryName: String): Any {
        val clazz = getEnumContainerClass(enumName)
        val field = clazz.getField(entryName)
        return field.get(null) ?: error("Enum entry $enumName.$entryName not found")
    }

    /**
     * Returns the metamodel class name of a [ModelInstance] by looking up its JVM class.
     *
     * @param instance The model instance.
     * @return The metamodel class name.
     * @throws IllegalStateException if the instance's class is not registered in this metamodel.
     */
    fun classNameOf(instance: ModelInstance): String {
        return classNameByClass[instance.javaClass]
            ?: error("Unknown ModelInstance class: ${instance.javaClass.name}")
    }

    /**
     * Creates a new instance of the given metamodel class.
     *
     * @param className The metamodel class name.
     * @return A new [ModelInstance] of the appropriate generated class.
     */
    fun createInstance(className: String): ModelInstance {
        val constructor = cachedConstructors[className]
            ?: error("No generated class for metamodel class '$className'")
        return constructor.newInstance() as ModelInstance
    }

    /**
     * Loads a [ModelData] into a live [Model] by instantiating all objects,
     * setting property values, and resolving link references.
     *
     * The returned [Model] is fully populated and ready for use with [Model.withModelProvider].
     *
     * @param modelData The raw model data to load.
     * @return A live [Model] backed by this metamodel.
     */
    fun loadModel(modelData: ModelData): Model {
        val classMetadataMap = metadata.classes

        val instances = LinkedHashMap<String, ModelInstance>()
        for (dataInstance in modelData.instances) {
            val instance = createInstance(dataInstance.className)
            instances[dataInstance.name] = instance
        }

        for (dataInstance in modelData.instances) {
            val instance = instances[dataInstance.name] ?: continue
            val meta = classMetadataMap[dataInstance.className] ?: continue

            for ((propName, mapping) in meta.propertyFields) {
                val propValue = dataInstance.properties[propName] ?: ModelDataPropertyValue.NullValue
                val jvmValue = convertPropertyValue(propValue, mapping)
                if (mapping.isCollection) {
                    val list: ArrayList<Any?> = if (jvmValue is Collection<*>) ArrayList(jvmValue) else ArrayList()
                    setField(instance, mapping.fieldIndex, list)
                } else {
                    setField(instance, mapping.fieldIndex, jvmValue)
                }
            }
        }

        val outgoingLinks = mutableMapOf<Pair<String, String>, MutableList<String>>()
        for (link in modelData.links) {
            if (link.sourceProperty != null) {
                outgoingLinks.getOrPut(link.sourceName to link.sourceProperty) { mutableListOf() }
                    .add(link.targetName)
            }
            if (link.targetProperty != null) {
                outgoingLinks.getOrPut(link.targetName to link.targetProperty) { mutableListOf() }
                    .add(link.sourceName)
            }
        }

        for (dataInstance in modelData.instances) {
            val instance = instances[dataInstance.name] ?: continue
            val meta = classMetadataMap[dataInstance.className] ?: continue

            for ((roleName, linkMapping) in meta.linkFields) {
                val targets = outgoingLinks[dataInstance.name to roleName] ?: continue
                val targetSet = LinkedHashSet<ModelInstance>()
                for (targetName in targets) {
                    instances[targetName]?.let { targetSet.add(it) }
                }
                setField(instance, linkMapping.fieldIndex, targetSet)
            }
        }

        return Model(this, modelData.metamodelPath, instances)
    }

    private fun setField(instance: ModelInstance, fieldIndex: Int, value: Any?) {
        val field = instance.javaClass.getField("prop_$fieldIndex")
        field.set(instance, value)
    }

    private fun convertPropertyValue(value: ModelDataPropertyValue, mapping: PropertyFieldMapping): Any? {
        return when (value) {
            is ModelDataPropertyValue.StringValue -> value.value
            is ModelDataPropertyValue.NumberValue -> convertNumber(value.value, mapping)
            is ModelDataPropertyValue.BooleanValue -> value.value
            is ModelDataPropertyValue.EnumValue -> {
                val enumName = mapping.enumType
                    ?: error("Property has enum value but no enum type in metamodel")
                resolveEnumValue(enumName, value.enumEntry)
            }
            is ModelDataPropertyValue.NullValue -> null
            is ModelDataPropertyValue.ListValue -> {
                value.values.map { convertPropertyValue(it, mapping.copy(isCollection = false)) }.toMutableList()
            }
        }
    }

    /**
     * Converts a numeric model data value to the correct JVM number type.
     *
     * Uses [PropertyFieldMapping.elementDescriptor] to determine the target type.
     *
     * @param value The raw double value from the model data.
     * @param mapping The property field mapping with element type info.
     * @return The converted number in the correct JVM type.
     */
    private fun convertNumber(value: Double, mapping: PropertyFieldMapping): Any {
        return when (mapping.elementDescriptor) {
            "I", "Ljava/lang/Integer;" -> value.toInt()
            "J", "Ljava/lang/Long;" -> value.toLong()
            "F", "Ljava/lang/Float;" -> value.toFloat()
            "D", "Ljava/lang/Double;" -> value
            else -> value
        }
    }
}
