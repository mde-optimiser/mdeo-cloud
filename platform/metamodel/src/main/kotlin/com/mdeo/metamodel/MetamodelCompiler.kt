package com.mdeo.metamodel

import com.mdeo.metamodel.data.*
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes

private const val MODEL_INSTANCE_CLASS = "com/mdeo/metamodel/ModelInstance"
private const val METAMODEL_CLASS = "com/mdeo/metamodel/Metamodel"

private fun toBinaryName(internalName: String): String {
    return internalName.replace('/', '.')
}

/**
 * Internal compiler that generates all JVM bytecode for a given [MetamodelData].
 *
 * Produces instance classes, enum value/container classes, and class container classes,
 * then loads them through a [MetamodelClassLoader] and assembles the final [Metamodel].
 *
 * @param data The metamodel data to compile.
 */
internal class MetamodelCompiler(private val data: MetamodelData) {
    private val classMap = data.classes.associateBy { it.name }
    private val allBytecodes = mutableMapOf<String, ByteArray>()
    private val classMetadataMap = mutableMapOf<String, ClassMetadata>()
    private val enumValueClassNames = mutableMapOf<String, String>()
    private val enumContainerClassNames = mutableMapOf<String, String>()
    private val classContainerClassNames = mutableMapOf<String, String>()
    private val instanceClassBinaryNames = mutableMapOf<String, String>()

    /**
     * Description of a single generated `prop_X` field within a class layout.
     *
     * @property fieldIndex The numeric index X in the field name `prop_X`.
     * @property fieldName The field name string (`prop_X`).
     * @property descriptor The JVM type descriptor.
     * @property sourceName The property or association role name this field backs.
     */
    data class FieldInfo(
        val fieldIndex: Int,
        val fieldName: String,
        val descriptor: String,
        val sourceName: String
    )

    /**
     * The complete field layout for a single class, including only the fields
     * it declares itself (not inherited ones).
     *
     * @property className The metamodel class name.
     * @property ownFields Fields declared directly by this class.
     * @property totalFieldCount Total number of fields including inherited.
     * @property ownFieldStartIndex Starting index for own fields (equals parent's totalFieldCount).
     * @property propertyMappings Property name to [PropertyFieldMapping] for own properties.
     * @property linkMappings Link role name to [LinkFieldMapping] for own association ends.
     */
    data class FieldLayout(
        val className: String,
        val ownFields: List<FieldInfo>,
        val totalFieldCount: Int,
        val ownFieldStartIndex: Int,
        val propertyMappings: Map<String, PropertyFieldMapping>,
        val linkMappings: Map<String, LinkFieldMapping>
    )

    /**
     * Runs the full compilation pipeline and returns the assembled [Metamodel].
     *
     * @param parentClassLoader The parent class loader for the generated [MetamodelClassLoader].
     * @return The compiled [Metamodel].
     */
    fun compile(parentClassLoader: ClassLoader): Metamodel {
        for (enumData in data.enums) {
            generateEnumValueClass(enumData)
            generateEnumContainerClass(enumData)
        }

        val fieldLayouts = buildFieldLayouts()

        for (classData in data.classes) {
            generateInstanceClass(classData, fieldLayouts)
        }

        for (classData in data.classes) {
            generateClassContainerClass(classData.name)
        }

        val classHierarchy = buildClassHierarchy()

        val binaryBytecodes = allBytecodes.map { (internalName, bytecode) ->
            toBinaryName(internalName) to bytecode
        }.toMap()
        val classLoader = MetamodelClassLoader(binaryBytecodes, parentClassLoader)

        val instanceClasses = mutableMapOf<String, Class<*>>()
        val enumContainerClassesMap = mutableMapOf<String, Class<*>>()
        val enumValueClassesMap = mutableMapOf<String, Class<*>>()
        val classContainerClassesMap = mutableMapOf<String, Class<*>>()

        for ((name, binaryName) in instanceClassBinaryNames) {
            instanceClasses[name] = classLoader.loadClass(binaryName)
        }
        for ((name, binaryName) in enumValueClassNames) {
            enumValueClassesMap[name] = classLoader.loadClass(binaryName)
        }
        for ((name, binaryName) in enumContainerClassNames) {
            enumContainerClassesMap[name] = classLoader.loadClass(binaryName)
        }
        for ((name, binaryName) in classContainerClassNames) {
            classContainerClassesMap[name] = classLoader.loadClass(binaryName)
        }

        val metadata = MetamodelMetadata(
            classes = classMetadataMap,
            classHierarchy = classHierarchy,
            enumValueClassNames = enumValueClassNames,
            enumContainerClassNames = enumContainerClassNames,
            classContainerClassNames = classContainerClassNames
        )

        return Metamodel(
            data = data,
            metadata = metadata,
            classLoader = classLoader,
            instanceClasses = instanceClasses,
            enumContainerClasses = enumContainerClassesMap,
            enumValueClasses = enumValueClassesMap,
            classContainerClasses = classContainerClassesMap
        )
    }

    /**
     * Computes field layouts for all classes in topological order (parents before children).
     *
     * @return Map from class name to its [FieldLayout].
     */
    private fun buildFieldLayouts(): Map<String, FieldLayout> {
        val layouts = mutableMapOf<String, FieldLayout>()
        val processed = mutableSetOf<String>()

        fun process(className: String): FieldLayout {
            layouts[className]?.let { return it }
            val classData = classMap[className] ?: error("Unknown class: $className")

            val parentFieldCount = if (classData.extends.isNotEmpty()) {
                val parentName = classData.extends.first()
                if (parentName !in processed) process(parentName)
                layouts[parentName]?.totalFieldCount ?: 0
            } else {
                0
            }

            var fieldIndex = parentFieldCount
            val ownFields = mutableListOf<FieldInfo>()
            val propertyMappings = mutableMapOf<String, PropertyFieldMapping>()
            val ownAssociationEndCount = data.associations.count {
                (it.source.className == className && it.source.name != null) ||
                    (it.target.className == className && it.target.name != null)
            }
            val linkMappings = HashMap<String, LinkFieldMapping>(maxOf(16, ownAssociationEndCount * 2))

            for (property in classData.properties) {
                val elementDescriptor = getPropertyElementDescriptor(property)
                val isCollection = property.multiplicity.upper != 1
                val fieldDescriptor = if (isCollection) "Ljava/util/List;" else elementDescriptor
                ownFields.add(FieldInfo(fieldIndex, "prop_$fieldIndex", fieldDescriptor, property.name))
                propertyMappings[property.name] = PropertyFieldMapping(
                    fieldIndex = fieldIndex,
                    fieldDescriptor = fieldDescriptor,
                    isCollection = isCollection,
                    enumType = property.enumType,
                    lower = property.multiplicity.lower,
                    upper = property.multiplicity.upper,
                    elementDescriptor = elementDescriptor
                )
                fieldIndex++
            }

            for (assoc in data.associations) {
                if (assoc.source.className == className && assoc.source.name != null) {
                    val sourceName = assoc.source.name
                    ownFields.add(FieldInfo(fieldIndex, "prop_$fieldIndex", "Ljava/util/Set;", sourceName))
                    linkMappings[sourceName] = LinkFieldMapping(
                        fieldIndex = fieldIndex,
                        fieldDescriptor = "Ljava/util/Set;",
                        isOutgoing = true,
                        oppositeClassName = assoc.target.className,
                        oppositeFieldName = assoc.target.name,
                        isMultiple = true,
                        edgeLabel = assoc.operator,
                        lower = assoc.source.multiplicity.lower,
                        upper = assoc.source.multiplicity.upper
                    )
                    fieldIndex++
                }
                if (assoc.target.className == className && assoc.target.name != null) {
                    val targetName = assoc.target.name
                    ownFields.add(FieldInfo(fieldIndex, "prop_$fieldIndex", "Ljava/util/Set;", targetName))
                    linkMappings[targetName] = LinkFieldMapping(
                        fieldIndex = fieldIndex,
                        fieldDescriptor = "Ljava/util/Set;",
                        isOutgoing = false,
                        oppositeClassName = assoc.source.className,
                        oppositeFieldName = assoc.source.name,
                        isMultiple = true,
                        edgeLabel = assoc.operator,
                        lower = assoc.target.multiplicity.lower,
                        upper = assoc.target.multiplicity.upper
                    )
                    fieldIndex++
                }
            }

            val layout = FieldLayout(
                className = className,
                ownFields = ownFields,
                totalFieldCount = fieldIndex,
                ownFieldStartIndex = parentFieldCount,
                propertyMappings = propertyMappings,
                linkMappings = linkMappings
            )
            layouts[className] = layout
            processed.add(className)
            return layout
        }

        for (classData in data.classes) {
            process(classData.name)
        }
        return layouts
    }

    /**
     * Returns the JVM type descriptor for the element type of a property.
     *
     * For mandatory single-valued properties (`lower >= 1 && upper == 1`) with a numeric or
     * boolean primitive type, returns the corresponding JVM primitive descriptor (`I`, `J`,
     * `F`, `D`, `Z`).  All other primitive properties (optional or multi-valued) continue to
     * use the boxed reference type so that `null` can represent "absent".
     *
     * For multi-valued properties the field uses `Ljava/util/List;` but the element type is
     * still needed for conversions; list elements are always stored as boxed objects, so this
     * method only returns a primitive descriptor when `upper == 1` (the field holds the value
     * directly).
     *
     * @param property The property definition.
     * @return The JVM element type descriptor string.
     */
    private fun getPropertyElementDescriptor(property: PropertyData): String {
        val primitiveType = property.primitiveType
        val enumType = property.enumType
        val usePrimitive = property.multiplicity.lower >= 1 && property.multiplicity.upper == 1

        return when {
            primitiveType != null -> when (primitiveType.lowercase()) {
                "int"     -> if (usePrimitive) "I"  else "Ljava/lang/Integer;"
                "long"    -> if (usePrimitive) "J"  else "Ljava/lang/Long;"
                "float"   -> if (usePrimitive) "F"  else "Ljava/lang/Float;"
                "double"  -> if (usePrimitive) "D"  else "Ljava/lang/Double;"
                "boolean" -> if (usePrimitive) "Z"  else "Ljava/lang/Boolean;"
                "string"  -> "Ljava/lang/String;"
                else      -> "Ljava/lang/Object;"
            }
            enumType != null -> {
                val enumValueInternalName = Metamodel.getEnumValueClassName(enumType)
                "L$enumValueInternalName;"
            }
            else -> "Ljava/lang/Object;"
        }
    }

    /**
     * Generates bytecode for the enum value class for [enumData].
     *
     * The generated class holds a single `entry` string field, and implements
     * `getEntry()`, `toString()`, `equals()`, and `hashCode()` based on that string.
     *
     * @param enumData The enum definition.
     */
    private fun generateEnumValueClass(enumData: EnumData) {
        val internalName = Metamodel.getEnumValueClassName(enumData.name)
        val binaryName = toBinaryName(internalName)
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)

        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, internalName, null, "java/lang/Object", null)

        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, "entry", "Ljava/lang/String;", null, null)?.visitEnd()

        val init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null)
        init.visitCode()
        init.visitVarInsn(Opcodes.ALOAD, 0)
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        init.visitVarInsn(Opcodes.ALOAD, 0)
        init.visitVarInsn(Opcodes.ALOAD, 1)
        init.visitFieldInsn(Opcodes.PUTFIELD, internalName, "entry", "Ljava/lang/String;")
        init.visitInsn(Opcodes.RETURN)
        init.visitMaxs(0, 0)
        init.visitEnd()

        val getEntry = cw.visitMethod(Opcodes.ACC_PUBLIC, "getEntry", "()Ljava/lang/String;", null, null)
        getEntry.visitCode()
        getEntry.visitVarInsn(Opcodes.ALOAD, 0)
        getEntry.visitFieldInsn(Opcodes.GETFIELD, internalName, "entry", "Ljava/lang/String;")
        getEntry.visitInsn(Opcodes.ARETURN)
        getEntry.visitMaxs(0, 0)
        getEntry.visitEnd()

        val toString = cw.visitMethod(Opcodes.ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null)
        toString.visitCode()
        toString.visitVarInsn(Opcodes.ALOAD, 0)
        toString.visitFieldInsn(Opcodes.GETFIELD, internalName, "entry", "Ljava/lang/String;")
        toString.visitInsn(Opcodes.ARETURN)
        toString.visitMaxs(0, 0)
        toString.visitEnd()

        generateEnumEquals(cw, internalName)

        val hashCode = cw.visitMethod(Opcodes.ACC_PUBLIC, "hashCode", "()I", null, null)
        hashCode.visitCode()
        hashCode.visitVarInsn(Opcodes.ALOAD, 0)
        hashCode.visitFieldInsn(Opcodes.GETFIELD, internalName, "entry", "Ljava/lang/String;")
        hashCode.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false)
        hashCode.visitInsn(Opcodes.IRETURN)
        hashCode.visitMaxs(0, 0)
        hashCode.visitEnd()

        cw.visitEnd()
        allBytecodes[internalName] = cw.toByteArray()
        enumValueClassNames[enumData.name] = binaryName
    }

    /**
     * Generates the `equals(Object)` method for an enum value class.
     *
     * Two enum values are equal if and only if they are the same instance or
     * are of the same class and have the same entry string.
     *
     * @param cw The [ClassWriter] to emit into.
     * @param internalName The JVM internal name of the enum value class.
     */
    private fun generateEnumEquals(cw: ClassWriter, internalName: String) {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null)
        mv.visitCode()

        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        val notSame = Label()
        mv.visitJumpInsn(Opcodes.IF_ACMPNE, notSame)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IRETURN)

        mv.visitLabel(notSame)
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitTypeInsn(Opcodes.INSTANCEOF, internalName)
        val isInstance = Label()
        mv.visitJumpInsn(Opcodes.IFNE, isInstance)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitInsn(Opcodes.IRETURN)

        mv.visitLabel(isInstance)
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitFieldInsn(Opcodes.GETFIELD, internalName, "entry", "Ljava/lang/String;")
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitTypeInsn(Opcodes.CHECKCAST, internalName)
        mv.visitFieldInsn(Opcodes.GETFIELD, internalName, "entry", "Ljava/lang/String;")
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
        mv.visitInsn(Opcodes.IRETURN)

        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * Generates bytecode for the enum container class for [enumData].
     *
     * The container class has a public static final field for each enum entry
     * (holding the corresponding enum value singleton), plus an `INSTANCE` singleton
     * of the container class itself.
     *
     * @param enumData The enum definition.
     */
    private fun generateEnumContainerClass(enumData: EnumData) {
        val valueInternalName = Metamodel.getEnumValueClassName(enumData.name)
        val internalName = Metamodel.getEnumContainerClassName(enumData.name)
        val binaryName = toBinaryName(internalName)
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)

        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, internalName, null, "java/lang/Object", null)

        for (entry in enumData.entries) {
            cw.visitField(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                entry, "L$valueInternalName;", null, null
            )?.visitEnd()
        }

        cw.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "INSTANCE", "L$internalName;", null, null
        )?.visitEnd()

        val clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        clinit.visitCode()
        for (entry in enumData.entries) {
            clinit.visitTypeInsn(Opcodes.NEW, valueInternalName)
            clinit.visitInsn(Opcodes.DUP)
            clinit.visitLdcInsn(entry)
            clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, valueInternalName, "<init>", "(Ljava/lang/String;)V", false)
            clinit.visitFieldInsn(Opcodes.PUTSTATIC, internalName, entry, "L$valueInternalName;")
        }
        clinit.visitTypeInsn(Opcodes.NEW, internalName)
        clinit.visitInsn(Opcodes.DUP)
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", "()V", false)
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "INSTANCE", "L$internalName;")
        clinit.visitInsn(Opcodes.RETURN)
        clinit.visitMaxs(0, 0)
        clinit.visitEnd()

        val init = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null)
        init.visitCode()
        init.visitVarInsn(Opcodes.ALOAD, 0)
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        init.visitInsn(Opcodes.RETURN)
        init.visitMaxs(0, 0)
        init.visitEnd()

        cw.visitEnd()
        allBytecodes[internalName] = cw.toByteArray()
        enumContainerClassNames[enumData.name] = binaryName
    }

    /**
     * Generates bytecode for the instance class of [classData].
     *
     * The generated class extends the parent instance class (or [ModelInstance] if no parent),
     * declares all own `prop_X` fields with the appropriate JVM types, and provides a
     * `(String instanceName, String className)` constructor.
     *
     * Also builds and stores [ClassMetadata] that includes inherited property and link mappings.
     *
     * @param classData The class definition.
     * @param fieldLayouts The precomputed field layouts for all classes.
     */
    private fun generateInstanceClass(classData: ClassData, fieldLayouts: Map<String, FieldLayout>) {
        val internalName = Metamodel.getInstanceClassName(classData.name)
        val binaryName = toBinaryName(internalName)
        val layout = fieldLayouts[classData.name] ?: error("No layout for ${classData.name}")

        val superInternalName = if (classData.extends.isNotEmpty()) {
            Metamodel.getInstanceClassName(classData.extends.first())
        } else {
            MODEL_INSTANCE_CLASS
        }

        val accessFlags = if (classData.isAbstract) {
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT
        } else {
            Opcodes.ACC_PUBLIC
        }

        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V11, accessFlags, internalName, null, superInternalName, null)

        for (field in layout.ownFields) {
            cw.visitField(Opcodes.ACC_PUBLIC, field.fieldName, field.descriptor, null, null)?.visitEnd()
        }

        val init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        init.visitCode()
        init.visitVarInsn(Opcodes.ALOAD, 0)
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superInternalName, "<init>", "()V", false)
        for (field in layout.ownFields) {
            when (field.descriptor) {
                "Ljava/util/Set;" -> {
                    init.visitVarInsn(Opcodes.ALOAD, 0)
                    init.visitTypeInsn(Opcodes.NEW, "java/util/HashSet")
                    init.visitInsn(Opcodes.DUP)
                    init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashSet", "<init>", "()V", false)
                    init.visitFieldInsn(Opcodes.PUTFIELD, internalName, field.fieldName, "Ljava/util/Set;")
                }
                "Ljava/util/List;" -> {
                    init.visitVarInsn(Opcodes.ALOAD, 0)
                    init.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList")
                    init.visitInsn(Opcodes.DUP)
                    init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false)
                    init.visitFieldInsn(Opcodes.PUTFIELD, internalName, field.fieldName, "Ljava/util/List;")
                }
            }
        }
        init.visitInsn(Opcodes.RETURN)
        init.visitMaxs(0, 0)
        init.visitEnd()

        val allPropertyMappings = mutableMapOf<String, PropertyFieldMapping>()
        val allLinkMappings = HashMap<String, LinkFieldMapping>(maxOf(16, layout.linkMappings.size * 2))
        collectInheritedMappings(classData.name, fieldLayouts, allPropertyMappings, allLinkMappings)

        generateGetPropertyByKey(cw, internalName, allPropertyMappings, allLinkMappings)
        generateSetPropertyByKey(cw, internalName, allPropertyMappings, allLinkMappings)
        generateCopyConstructor(cw, internalName, superInternalName, layout.ownFields)
        generateCopy(cw, internalName, classData.isAbstract)
        generateCopyReferences(cw, internalName, allLinkMappings)
        generateWriteProperties(cw, internalName, allPropertyMappings)
        generateReadProperties(cw, internalName, allPropertyMappings, classData.isAbstract)
        generateWriteLinkFields(cw, internalName, allLinkMappings)
        generateReadLinkFields(cw, internalName, allLinkMappings)

        cw.visitEnd()
        allBytecodes[internalName] = cw.toByteArray()
        instanceClassBinaryNames[classData.name] = binaryName

        classMetadataMap[classData.name] = ClassMetadata(
            className = classData.name,
            binaryName = binaryName,
            propertyFields = allPropertyMappings,
            linkFields = allLinkMappings,
            totalFieldCount = layout.totalFieldCount
        )
    }

    /**
     * Recursively collects all property and link field mappings for [className],
     * including those inherited from parent classes, into the provided maps.
     *
     * @param className The class name to collect mappings for.
     * @param fieldLayouts The precomputed field layouts for all classes.
     * @param propertyMappings Output map accumulating property name to [PropertyFieldMapping].
     * @param linkMappings Output map accumulating role name to [LinkFieldMapping].
     */
    private fun collectInheritedMappings(
        className: String,
        fieldLayouts: Map<String, FieldLayout>,
        propertyMappings: MutableMap<String, PropertyFieldMapping>,
        linkMappings: MutableMap<String, LinkFieldMapping>
    ) {
        val classData = classMap[className] ?: return
        if (classData.extends.isNotEmpty()) {
            collectInheritedMappings(classData.extends.first(), fieldLayouts, propertyMappings, linkMappings)
        }
        val layout = fieldLayouts[className] ?: return
        propertyMappings.putAll(layout.propertyMappings)
        linkMappings.putAll(layout.linkMappings)
    }

    /**
     * Generates the `getPropertyByKey(String): Any?` method for an instance class.
     *
     * Emits a chain of if-else comparisons that maps each property/link name to
     * a GETFIELD on the backing `prop_X` Set, extracting the single element for
     * single-multiplicity fields or returning the Set as-is for multi-valued fields.
     * Throws [IllegalArgumentException] if the key does not match any known field.
     *
     * @param cw The [ClassWriter] to emit into.
     * @param internalName The JVM internal name of the instance class.
     * @param allPropertyMappings All property mappings (own + inherited).
     * @param allLinkMappings All link mappings (own + inherited).
     */
    private fun generateGetPropertyByKey(
        cw: ClassWriter,
        internalName: String,
        allPropertyMappings: Map<String, PropertyFieldMapping>,
        allLinkMappings: Map<String, LinkFieldMapping>
    ) {
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC, "getPropertyByKey",
            "(Ljava/lang/String;)Ljava/lang/Object;", null, null
        )
        mv.visitCode()

        for ((name, mapping) in allPropertyMappings) {
            emitPropertyGetBranch(mv, internalName, name, mapping.fieldIndex, mapping.fieldDescriptor)
        }
        for ((name, mapping) in allLinkMappings) {
            emitLinkGetBranch(mv, internalName, name, mapping.fieldIndex, mapping.upper == 1)
        }

        emitThrowUnknownKey(mv)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * Generates the `setPropertyByKey(String, Any?)` method for an instance class.
     *
     * Emits a chain of if-else comparisons that maps each property/link name to
     * a modification of the backing `prop_X` Set: single-multiplicity fields clear and
     * re-add; multi-valued fields replace the entire Set.
     * Throws [IllegalArgumentException] if the key does not match any known field.
     *
     * @param cw The [ClassWriter] to emit into.
     * @param internalName The JVM internal name of the instance class.
     * @param allPropertyMappings All property mappings (own + inherited).
     * @param allLinkMappings All link mappings (own + inherited).
     */
    private fun generateSetPropertyByKey(
        cw: ClassWriter,
        internalName: String,
        allPropertyMappings: Map<String, PropertyFieldMapping>,
        allLinkMappings: Map<String, LinkFieldMapping>
    ) {
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC, "setPropertyByKey",
            "(Ljava/lang/String;Ljava/lang/Object;)V", null, null
        )
        mv.visitCode()

        for ((name, mapping) in allPropertyMappings) {
            emitPropertySetBranch(mv, internalName, name, mapping.fieldIndex, mapping.isCollection, mapping.fieldDescriptor)
        }
        for ((name, mapping) in allLinkMappings) {
            emitLinkSetBranch(mv, internalName, name, mapping.fieldIndex, mapping.upper == 1)
        }

        emitThrowUnknownKey(mv)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * Emits a get-branch for a regular property field (direct value or List).
     *
     * If the field stores a JVM primitive (`I`, `J`, `F`, `D`, `Z`), boxes it before
     * the `ARETURN` so that the `getPropertyByKey(String): Any?` signature is satisfied.
     */
    private fun emitPropertyGetBranch(
        mv: org.objectweb.asm.MethodVisitor,
        internalName: String,
        name: String,
        fieldIndex: Int,
        fieldDescriptor: String
    ) {
        val nextLabel = Label()
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitLdcInsn(name)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
        mv.visitJumpInsn(Opcodes.IFEQ, nextLabel)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitFieldInsn(Opcodes.GETFIELD, internalName, "prop_$fieldIndex", fieldDescriptor)
        when (fieldDescriptor) {
            "I" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)
            "J" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long",    "valueOf", "(J)Ljava/lang/Long;",    false)
            "F" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float",   "valueOf", "(F)Ljava/lang/Float;",   false)
            "D" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double",  "valueOf", "(D)Ljava/lang/Double;",  false)
            "Z" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
        }
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitLabel(nextLabel)
    }

    /**
     * Emits a set-branch for a regular property field (direct value or List).
     *
     * `setPropertyByKey` receives an `Object` in local 2.  For primitive fields the
     * Object is a boxed wrapper; it must be cast and unboxed before the `PUTFIELD`.
     */
    private fun emitPropertySetBranch(
        mv: org.objectweb.asm.MethodVisitor,
        internalName: String,
        name: String,
        fieldIndex: Int,
        isCollection: Boolean,
        fieldDescriptor: String
    ) {
        val nextLabel = Label()
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitLdcInsn(name)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
        mv.visitJumpInsn(Opcodes.IFEQ, nextLabel)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 2)
        if (isCollection) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/List")
        } else when (fieldDescriptor) {
            "I" -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue",     "()I", false)
            }
            "J" -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "longValue",    "()J", false)
            }
            "F" -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "floatValue",   "()F", false)
            }
            "D" -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "doubleValue",  "()D", false)
            }
            "Z" -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false)
            }
            // Boxed numeric fields (optional/nullable multiplicity) have the same
            // problem: coerce through Number, then re-box to the declared wrapper type.
            "Ljava/lang/Integer;" -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)
            }
            "Ljava/lang/Long;" -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)
            }
            "Ljava/lang/Float;" -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false)
            }
            "Ljava/lang/Double;" -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false)
            }
            "Ljava/lang/Object;" -> { /* no cast needed */ }
            else -> {
                val targetType = fieldDescriptor.removePrefix("L").removeSuffix(";")
                mv.visitTypeInsn(Opcodes.CHECKCAST, targetType)
            }
        }
        mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "prop_$fieldIndex", fieldDescriptor)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitLabel(nextLabel)
    }

    /**
     * Emits a get-branch for a link (association end) field — always Set-backed. 
     */
    private fun emitLinkGetBranch(
        mv: org.objectweb.asm.MethodVisitor,
        internalName: String,
        name: String,
        fieldIndex: Int,
        isSingle: Boolean
    ) {
        val nextLabel = Label()
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitLdcInsn(name)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
        mv.visitJumpInsn(Opcodes.IFEQ, nextLabel)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitFieldInsn(Opcodes.GETFIELD, internalName, "prop_$fieldIndex", "Ljava/util/Set;")
        if (isSingle) {
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "com/mdeo/metamodel/MultiplicityAccessHelper",
                "extractFirst",
                "(Ljava/util/Set;)Ljava/lang/Object;",
                false
            )
        }
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitLabel(nextLabel)
    }

    /**
     * Emits a set-branch for a link (association end) field — always Set-backed. 
     */
    private fun emitLinkSetBranch(
        mv: org.objectweb.asm.MethodVisitor,
        internalName: String,
        name: String,
        fieldIndex: Int,
        isSingle: Boolean
    ) {
        val nextLabel = Label()
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitLdcInsn(name)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
        mv.visitJumpInsn(Opcodes.IFEQ, nextLabel)
        if (isSingle) {
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitFieldInsn(Opcodes.GETFIELD, internalName, "prop_$fieldIndex", "Ljava/util/Set;")
            mv.visitVarInsn(Opcodes.ALOAD, 2)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "com/mdeo/metamodel/MultiplicityAccessHelper",
                "setSingle",
                "(Ljava/util/Set;Ljava/lang/Object;)V",
                false
            )
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitVarInsn(Opcodes.ALOAD, 2)
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Set")
            mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "prop_$fieldIndex", "Ljava/util/Set;")
        }
        mv.visitInsn(Opcodes.RETURN)
        mv.visitLabel(nextLabel)
    }

    /**
     * Emits bytecode that creates and throws an [IllegalArgumentException] with
     * a message containing the unknown key. Used as the fallthrough of the
     * `getPropertyByKey` / `setPropertyByKey` if-else chains.
     *
     * @param mv The method visitor.
     */
    private fun emitThrowUnknownKey(mv: org.objectweb.asm.MethodVisitor) {
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalArgumentException")
        mv.visitInsn(Opcodes.DUP)
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
        mv.visitInsn(Opcodes.DUP)
        mv.visitLdcInsn("Unknown property key: ")
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;)V", false)
        mv.visitInsn(Opcodes.ATHROW)
    }

    /**
     * Generates bytecode for a class container class for the given [className].
     *
     * The generated class has an `INSTANCE` singleton and an `all()` method that
     * delegates to the thread-local [Metamodel.ModelProvider] to return model instances.
     *
     * @param className The metamodel class name.
     */
    private fun generateClassContainerClass(className: String) {
        val internalName = Metamodel.getClassContainerClassName(className)
        val binaryName = toBinaryName(internalName)
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)

        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, internalName, null, "java/lang/Object", null)

        cw.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "INSTANCE", "L$internalName;", null, null
        )?.visitEnd()

        val clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        clinit.visitCode()
        clinit.visitTypeInsn(Opcodes.NEW, internalName)
        clinit.visitInsn(Opcodes.DUP)
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", "()V", false)
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "INSTANCE", "L$internalName;")
        clinit.visitInsn(Opcodes.RETURN)
        clinit.visitMaxs(0, 0)
        clinit.visitEnd()

        val init = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null)
        init.visitCode()
        init.visitVarInsn(Opcodes.ALOAD, 0)
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        init.visitInsn(Opcodes.RETURN)
        init.visitMaxs(0, 0)
        init.visitEnd()

        val allMv = cw.visitMethod(Opcodes.ACC_PUBLIC, "all", "()Ljava/util/List;", null, null)
        allMv.visitCode()
        allMv.visitMethodInsn(Opcodes.INVOKESTATIC, METAMODEL_CLASS, "getCurrentModelProvider", "()Ljava/lang/ThreadLocal;", false)
        allMv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;", false)
        allMv.visitTypeInsn(Opcodes.CHECKCAST, "com/mdeo/metamodel/Metamodel\$ModelProvider")
        allMv.visitLdcInsn(className)
        allMv.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            "com/mdeo/metamodel/Metamodel\$ModelProvider",
            "getAllInstances",
            "(Ljava/lang/String;)Ljava/util/List;",
            true
        )
        allMv.visitInsn(Opcodes.ARETURN)
        allMv.visitMaxs(0, 0)
        allMv.visitEnd()

        cw.visitEnd()
        allBytecodes[internalName] = cw.toByteArray()
        classContainerClassNames[className] = binaryName
    }

    /**
     * Generates the copy constructor `<init>(SameClass source)V` for an instance class.
     *
     * The copy constructor chains to the parent's copy constructor (or the no-arg
     * [ModelInstance] constructor if this class directly extends [ModelInstance]). It then
     * copies all OWN property fields from [source]:
     *
     * - **Set fields** (link association ends) are initialised to a presized [java.util.HashSet]
     *   with capacity derived from `source.field.size()`, so that [generateCopyReferences] can
     *   populate the Set in-place without any resize.
     * - **List fields** (multi-valued scalar properties) are copied as a new
     *   [java.util.ArrayList] constructed from the source, providing capacity presizing.
     * - **All other fields** (single-valued scalars) are copied directly.
     *
     * @param cw The [ClassWriter] to emit into.
     * @param internalName The JVM internal name of the class being generated.
     * @param superInternalName The JVM internal name of the direct superclass.
     * @param ownFields Only the fields declared by this class (not inherited).
     */
    private fun generateCopyConstructor(
        cw: ClassWriter,
        internalName: String,
        superInternalName: String,
        ownFields: List<FieldInfo>
    ) {
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC, "<init>",
            "(L$internalName;)V", null, null
        )
        mv.visitCode()

        mv.visitVarInsn(Opcodes.ALOAD, 0)
        if (superInternalName == MODEL_INSTANCE_CLASS) {
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, MODEL_INSTANCE_CLASS, "<init>", "()V", false)
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, 1)
            mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL, superInternalName, "<init>",
                "(L$superInternalName;)V", false
            )
        }

        for (field in ownFields) {
            when (field.descriptor) {
                "Ljava/util/Set;" -> {
                    val elseNullLabel = Label()
                    val endSizeLabel = Label()
                    mv.visitVarInsn(Opcodes.ALOAD, 0)
                    mv.visitTypeInsn(Opcodes.NEW, "java/util/HashSet")
                    mv.visitInsn(Opcodes.DUP)
                    mv.visitVarInsn(Opcodes.ALOAD, 1)
                    mv.visitFieldInsn(Opcodes.GETFIELD, internalName, field.fieldName, "Ljava/util/Set;")
                    mv.visitVarInsn(Opcodes.ASTORE, 2)
                    mv.visitVarInsn(Opcodes.ALOAD, 2)
                    mv.visitJumpInsn(Opcodes.IFNULL, elseNullLabel)
                    mv.visitVarInsn(Opcodes.ALOAD, 2)
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "size", "()I", true)
                    mv.visitJumpInsn(Opcodes.GOTO, endSizeLabel)
                    mv.visitLabel(elseNullLabel)
                    mv.visitInsn(Opcodes.ICONST_0)
                    mv.visitLabel(endSizeLabel)
                    mv.visitInsn(Opcodes.DUP)
                    mv.visitInsn(Opcodes.ICONST_1)
                    mv.visitInsn(Opcodes.ISHR)
                    mv.visitInsn(Opcodes.IADD)
                    mv.visitInsn(Opcodes.ICONST_1)
                    mv.visitInsn(Opcodes.IADD)
                    mv.visitMethodInsn(
                        Opcodes.INVOKESPECIAL, "java/util/HashSet", "<init>", "(I)V", false
                    )
                    mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, field.fieldName, "Ljava/util/Set;")
                }
                "Ljava/util/List;" -> {
                    val skipLabel = Label()
                    mv.visitVarInsn(Opcodes.ALOAD, 1)
                    mv.visitFieldInsn(Opcodes.GETFIELD, internalName, field.fieldName, "Ljava/util/List;")
                    mv.visitVarInsn(Opcodes.ASTORE, 2)
                    mv.visitVarInsn(Opcodes.ALOAD, 2)
                    mv.visitJumpInsn(Opcodes.IFNULL, skipLabel)
                    mv.visitVarInsn(Opcodes.ALOAD, 0)
                    mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList")
                    mv.visitInsn(Opcodes.DUP)
                    mv.visitVarInsn(Opcodes.ALOAD, 2)
                    mv.visitMethodInsn(
                        Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>",
                        "(Ljava/util/Collection;)V", false
                    )
                    mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, field.fieldName, "Ljava/util/List;")
                    mv.visitLabel(skipLabel)
                }
                else -> {
                    mv.visitVarInsn(Opcodes.ALOAD, 0)
                    mv.visitVarInsn(Opcodes.ALOAD, 1)
                    mv.visitFieldInsn(Opcodes.GETFIELD, internalName, field.fieldName, field.descriptor)
                    mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, field.fieldName, field.descriptor)
                }
            }
        }

        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * Generates the `copy(): ModelInstance` override for concrete instance classes.
     *
     * For abstract classes this method emits nothing — they inherit the abstract declaration
     * from [ModelInstance] and concrete subclasses provide the implementation.
     *
     * The generated method creates a new instance of the exact same concrete class via the
     * copy constructor `<init>(SameClass)V`, which copies all property fields and leaves
     * link Set fields null. The caller must then complete the copy by calling
     * [ModelInstance.copyReferences] on this instance.
     *
     * @param cw The [ClassWriter] to emit into.
     * @param internalName The JVM internal name of the class being generated.
     * @param isAbstract Whether the class is abstract (no method generated if true).
     */
    private fun generateCopy(cw: ClassWriter, internalName: String, isAbstract: Boolean) {
        if (isAbstract) {
            return
        }

        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC, "copy",
            "()L$MODEL_INSTANCE_CLASS;", null, null
        )
        mv.visitCode()

        mv.visitTypeInsn(Opcodes.NEW, internalName)
        mv.visitInsn(Opcodes.DUP)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL, internalName, "<init>",
            "(L$internalName;)V", false
        )
        mv.visitInsn(Opcodes.ARETURN)

        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * Generates the `copyReferences(ModelInstance, Map)` method for an instance class.
     *
     * All link fields are Set-backed. Since [generateCopyConstructor] already creates a
     * presized [java.util.HashSet] for each link field on the copy, this method simply
     * populates the existing Set in-place: it reads the source's Set, iterates it, remaps
     * each element through [instanceMap], and adds the result to the already-allocated Set
     * on [target]. No allocation occurs here.
     *
     * @param cw The [ClassWriter] to emit into.
     * @param internalName The JVM internal name of the instance class.
     * @param allLinkMappings All link mappings (own + inherited).
     */
    private fun generateCopyReferences(
        cw: ClassWriter,
        internalName: String,
        allLinkMappings: Map<String, LinkFieldMapping>
    ) {
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC, "copyReferences",
            "(L$MODEL_INSTANCE_CLASS;Ljava/util/Map;)V", null, null
        )
        mv.visitCode()

        if (allLinkMappings.isNotEmpty()) {
            mv.visitVarInsn(Opcodes.ALOAD, 1)
            mv.visitTypeInsn(Opcodes.CHECKCAST, internalName)
            mv.visitVarInsn(Opcodes.ASTORE, 3)

            for ((_, mapping) in allLinkMappings) {
                val fieldName = "prop_${mapping.fieldIndex}"

                val skipLabel = Label()
                val loopLabel = Label()
                val loopEndLabel = Label()
                val elFoundLabel = Label()

                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitFieldInsn(Opcodes.GETFIELD, internalName, fieldName, "Ljava/util/Set;")
                mv.visitVarInsn(Opcodes.ASTORE, 4)
                mv.visitVarInsn(Opcodes.ALOAD, 4)
                mv.visitJumpInsn(Opcodes.IFNULL, skipLabel)

                mv.visitVarInsn(Opcodes.ALOAD, 3)
                mv.visitFieldInsn(Opcodes.GETFIELD, internalName, fieldName, "Ljava/util/Set;")
                mv.visitVarInsn(Opcodes.ASTORE, 5)

                mv.visitVarInsn(Opcodes.ALOAD, 4)
                mv.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator",
                    "()Ljava/util/Iterator;", true
                )
                mv.visitVarInsn(Opcodes.ASTORE, 6)

                mv.visitLabel(loopLabel)
                mv.visitVarInsn(Opcodes.ALOAD, 6)
                mv.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true
                )
                mv.visitJumpInsn(Opcodes.IFEQ, loopEndLabel)

                mv.visitVarInsn(Opcodes.ALOAD, 6)
                mv.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next",
                    "()Ljava/lang/Object;", true
                )
                mv.visitVarInsn(Opcodes.ASTORE, 7)

                mv.visitVarInsn(Opcodes.ALOAD, 2)
                mv.visitVarInsn(Opcodes.ALOAD, 7)
                mv.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE, "java/util/Map", "get",
                    "(Ljava/lang/Object;)Ljava/lang/Object;", true
                )
                mv.visitInsn(Opcodes.DUP)
                mv.visitJumpInsn(Opcodes.IFNONNULL, elFoundLabel)
                mv.visitInsn(Opcodes.POP)
                mv.visitVarInsn(Opcodes.ALOAD, 7)
                mv.visitLabel(elFoundLabel)

                mv.visitVarInsn(Opcodes.ALOAD, 5)
                mv.visitInsn(Opcodes.SWAP)
                mv.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE, "java/util/Set", "add",
                    "(Ljava/lang/Object;)Z", true
                )
                mv.visitInsn(Opcodes.POP)
                mv.visitJumpInsn(Opcodes.GOTO, loopLabel)

                mv.visitLabel(loopEndLabel)

                mv.visitLabel(skipLabel)
            }
        }

        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * Generates `writeProperties(DataOutput)` which serializes all scalar and list
     * property field values to a [java.io.DataOutput] in field-index order.
     *
     * Link (Set) fields are skipped — they are handled by [generateWriteLinkFields].
     * For each property field the method writes a one-byte null marker (0 = null, 1 = present)
     * followed by the raw value if present, using the JVM type to select the encoding:
     *
     * - `Integer` → `writeInt`
     * - `Long` → `writeLong`
     * - `Float` → `writeFloat`
     * - `Double` → `writeDouble`
     * - `Boolean` → `writeByte` (0 or 1)
     * - `String` → `writeUTF`
     * - Enum value → `writeUTF(entry)` via the generated `getEntry()` method
     * - `List` → `writeInt(size)` then each element with the same null-marker + value scheme
     *
     * @param cw The [ClassWriter] to emit into.
     * @param internalName The JVM internal name of the instance class.
     * @param allPropertyMappings All property mappings (own + inherited).
     */
    private fun generateWriteProperties(
        cw: ClassWriter,
        internalName: String,
        allPropertyMappings: Map<String, PropertyFieldMapping>
    ) {
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC, "writeProperties",
            "(Ljava/io/DataOutput;)V", null, null
        )
        mv.visitCode()

        val sortedMappings = allPropertyMappings.values.sortedBy { it.fieldIndex }
        for (mapping in sortedMappings) {
            val fieldName = "prop_${mapping.fieldIndex}"

            if (mapping.isCollection) {
                emitWriteListField(mv, internalName, fieldName, mapping)
            } else {
                emitWriteScalarField(mv, internalName, fieldName, mapping)
            }
        }

        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * Emits bytecode to write a single scalar property field to [java.io.DataOutput].
     *
     * For JVM primitive fields (`I`, `J`, `F`, `D`, `Z`) the value is always present, so a
     * fixed present-marker byte (`1`) is written followed immediately by the raw primitive
     * value, without loading the value into an object local first.
     *
     * For nullable reference fields the original null-check path is used: a marker byte
     * (`0` = null, `1` = present) followed by the value when present.
     *
     * @param mv The method visitor.
     * @param internalName The JVM internal name of the class owning the field.
     * @param fieldName The field name (`prop_X`).
     * @param mapping The property field mapping with type information.
     */
    private fun emitWriteScalarField(
        mv: org.objectweb.asm.MethodVisitor,
        internalName: String,
        fieldName: String,
        mapping: PropertyFieldMapping
    ) {
        val descriptor = mapping.fieldDescriptor

        when (descriptor) {
            "I", "J", "F", "D", "Z" -> {
                // Mandatory primitive field: always present — write marker=1 then raw value.
                mv.visitVarInsn(Opcodes.ALOAD, 1)
                mv.visitInsn(Opcodes.ICONST_1)
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataOutput", "writeByte", "(I)V", true)
                mv.visitVarInsn(Opcodes.ALOAD, 1)
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitFieldInsn(Opcodes.GETFIELD, internalName, fieldName, descriptor)
                when (descriptor) {
                    "I" -> mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataOutput", "writeInt",    "(I)V", true)
                    "J" -> mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataOutput", "writeLong",   "(J)V", true)
                    "F" -> mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataOutput", "writeFloat",  "(F)V", true)
                    "D" -> mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataOutput", "writeDouble", "(D)V", true)
                    "Z" -> mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataOutput", "writeByte",   "(I)V", true)
                }
            }
            else -> {
                // Nullable reference field: null-check path.
                val nullLabel = Label()
                val endLabel = Label()

                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitFieldInsn(Opcodes.GETFIELD, internalName, fieldName, descriptor)
                mv.visitVarInsn(Opcodes.ASTORE, 2)
                mv.visitVarInsn(Opcodes.ALOAD, 2)
                mv.visitJumpInsn(Opcodes.IFNULL, nullLabel)

                mv.visitVarInsn(Opcodes.ALOAD, 1)
                mv.visitInsn(Opcodes.ICONST_1)
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataOutput", "writeByte", "(I)V", true)

                emitWriteValueFromLocal(mv, 2, mapping.elementDescriptor)

                mv.visitJumpInsn(Opcodes.GOTO, endLabel)

                mv.visitLabel(nullLabel)
                mv.visitVarInsn(Opcodes.ALOAD, 1)
                mv.visitInsn(Opcodes.ICONST_0)
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataOutput", "writeByte", "(I)V", true)

                mv.visitLabel(endLabel)
            }
        }
    }

    /**
     * Emits bytecode to write a List property field to [java.io.DataOutput].
     *
     * Writes the list size as an int, then for each element writes a null marker
     * and the element value using the element descriptor.
     *
     * @param mv The method visitor.
     * @param internalName The JVM internal name of the class owning the field.
     * @param fieldName The field name (`prop_X`).
     * @param mapping The property field mapping with element type information.
     */
    private fun emitWriteListField(
        mv: org.objectweb.asm.MethodVisitor,
        internalName: String,
        fieldName: String,
        mapping: PropertyFieldMapping
    ) {
        val nullLabel = Label()
        val endLabel = Label()
        val loopLabel = Label()
        val loopEndLabel = Label()
        val elemNullLabel = Label()
        val elemEndLabel = Label()

        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitFieldInsn(Opcodes.GETFIELD, internalName, fieldName, "Ljava/util/List;")
        mv.visitVarInsn(Opcodes.ASTORE, 2)
        mv.visitVarInsn(Opcodes.ALOAD, 2)
        mv.visitJumpInsn(Opcodes.IFNULL, nullLabel)

        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitVarInsn(Opcodes.ALOAD, 2)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataOutput", "writeInt", "(I)V", true)

        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitVarInsn(Opcodes.ISTORE, 3)
        mv.visitVarInsn(Opcodes.ALOAD, 2)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true)
        mv.visitVarInsn(Opcodes.ISTORE, 4)

        mv.visitLabel(loopLabel)
        mv.visitVarInsn(Opcodes.ILOAD, 3)
        mv.visitVarInsn(Opcodes.ILOAD, 4)
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, loopEndLabel)

        mv.visitVarInsn(Opcodes.ALOAD, 2)
        mv.visitVarInsn(Opcodes.ILOAD, 3)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true)
        mv.visitVarInsn(Opcodes.ASTORE, 5)

        mv.visitVarInsn(Opcodes.ALOAD, 5)
        mv.visitJumpInsn(Opcodes.IFNULL, elemNullLabel)

        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataOutput", "writeByte", "(I)V", true)
        emitWriteValueFromLocal(mv, 5, mapping.elementDescriptor)
        mv.visitJumpInsn(Opcodes.GOTO, elemEndLabel)

        mv.visitLabel(elemNullLabel)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataOutput", "writeByte", "(I)V", true)

        mv.visitLabel(elemEndLabel)
        mv.visitIincInsn(3, 1)
        mv.visitJumpInsn(Opcodes.GOTO, loopLabel)

        mv.visitLabel(loopEndLabel)
        mv.visitJumpInsn(Opcodes.GOTO, endLabel)

        mv.visitLabel(nullLabel)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitInsn(Opcodes.ICONST_M1)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataOutput", "writeInt", "(I)V", true)

        mv.visitLabel(endLabel)
    }

    /**
     * Emits bytecode that writes a single non-null value from local variable [localIndex]
     * to the [java.io.DataOutput] in local 1, based on the element descriptor.
     *
     * @param mv The method visitor.
     * @param localIndex Local variable index holding the object reference.
     * @param elementDescriptor The JVM element type descriptor (e.g. `Ljava/lang/Integer;`).
     */
    private fun emitWriteValueFromLocal(
        mv: org.objectweb.asm.MethodVisitor,
        localIndex: Int,
        elementDescriptor: String
    ) {
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        when (elementDescriptor) {
            "Ljava/lang/Integer;" -> {
                mv.visitVarInsn(Opcodes.ALOAD, localIndex)
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Integer")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false)
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataOutput", "writeInt", "(I)V", true)
            }
            "Ljava/lang/Long;" -> {
                mv.visitVarInsn(Opcodes.ALOAD, localIndex)
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Long")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false)
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataOutput", "writeLong", "(J)V", true)
            }
            "Ljava/lang/Float;" -> {
                mv.visitVarInsn(Opcodes.ALOAD, localIndex)
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Float")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false)
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataOutput", "writeFloat", "(F)V", true)
            }
            "Ljava/lang/Double;" -> {
                mv.visitVarInsn(Opcodes.ALOAD, localIndex)
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Double")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false)
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataOutput", "writeDouble", "(D)V", true)
            }
            "Ljava/lang/Boolean;" -> {
                mv.visitVarInsn(Opcodes.ALOAD, localIndex)
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false)
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataOutput", "writeByte", "(I)V", true)
            }
            "Ljava/lang/String;" -> {
                mv.visitVarInsn(Opcodes.ALOAD, localIndex)
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String")
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataOutput", "writeUTF", "(Ljava/lang/String;)V", true)
            }
            else -> {
                mv.visitVarInsn(Opcodes.ALOAD, localIndex)
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false)
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataOutput", "writeUTF", "(Ljava/lang/String;)V", true)
            }
        }
    }

    /**
     * Generates `readProperties(DataInput, Metamodel)` which deserializes all scalar and
     * list property field values from a [java.io.DataInput] in field-index order, setting
     * each field on this instance.
     *
     * Link (Set) fields are skipped — they are handled by [generateReadLinkFields].
     * For abstract classes a no-op implementation is emitted (the concrete subclass
     * will handle all fields including inherited ones).
     *
     * @param cw The [ClassWriter] to emit into.
     * @param internalName The JVM internal name of the instance class.
     * @param allPropertyMappings All property mappings (own + inherited).
     * @param isAbstract Whether this class is abstract; abstract classes emit a no-op.
     */
    private fun generateReadProperties(
        cw: ClassWriter,
        internalName: String,
        allPropertyMappings: Map<String, PropertyFieldMapping>,
        isAbstract: Boolean
    ) {
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC, "readProperties",
            "(Ljava/io/DataInput;L$METAMODEL_CLASS;)V", null, null
        )
        mv.visitCode()

        if (!isAbstract) {
            val sortedMappings = allPropertyMappings.values.sortedBy { it.fieldIndex }
            for (mapping in sortedMappings) {
                val fieldName = "prop_${mapping.fieldIndex}"
                if (mapping.isCollection) {
                    emitReadListField(mv, internalName, fieldName, mapping)
                } else {
                    emitReadScalarField(mv, internalName, fieldName, mapping)
                }
            }
        }

        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * Emits bytecode to read a single scalar property field from [java.io.DataInput].
     *
     * For JVM primitive fields (`I`, `J`, `F`, `D`, `Z`) the wire format has a one-byte
     * present-marker (always written as `1` by [emitWriteScalarField]) followed by the raw
     * primitive value.  If the marker is `0` (defensive; should not occur for mandatory
     * primitives) the field is left at its JVM default (`0` / `false`).
     *
     * For nullable reference fields the original null-check path is used.
     *
     * @param mv The method visitor.
     * @param internalName The JVM internal name of the class owning the field.
     * @param fieldName The field name (`prop_X`).
     * @param mapping The property field mapping with type information.
     */
    private fun emitReadScalarField(
        mv: org.objectweb.asm.MethodVisitor,
        internalName: String,
        fieldName: String,
        mapping: PropertyFieldMapping
    ) {
        val descriptor = mapping.fieldDescriptor

        when (descriptor) {
            "I", "J", "F", "D", "Z" -> {
                val endLabel = Label()
                mv.visitVarInsn(Opcodes.ALOAD, 1)
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataInput", "readByte", "()B", true)
                mv.visitJumpInsn(Opcodes.IFEQ, endLabel) 
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitVarInsn(Opcodes.ALOAD, 1)
                when (descriptor) {
                    "I" -> mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataInput", "readInt",    "()I", true)
                    "J" -> mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataInput", "readLong",   "()J", true)
                    "F" -> mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataInput", "readFloat",  "()F", true)
                    "D" -> mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataInput", "readDouble", "()D", true)
                    "Z" -> mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataInput", "readByte",   "()B", true)
                }
                mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, fieldName, descriptor)
                mv.visitLabel(endLabel)
            }
            else -> {
                val nullLabel = Label()
                val endLabel = Label()

                mv.visitVarInsn(Opcodes.ALOAD, 1)
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataInput", "readByte", "()B", true)
                mv.visitJumpInsn(Opcodes.IFEQ, nullLabel)

                mv.visitVarInsn(Opcodes.ALOAD, 0)
                emitReadValue(mv, mapping.elementDescriptor, mapping.enumType)
                if (mapping.enumType != null) {
                    val enumInternalName = descriptor.removePrefix("L").removeSuffix(";")
                    mv.visitTypeInsn(Opcodes.CHECKCAST, enumInternalName)
                }
                mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, fieldName, descriptor)

                mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                mv.visitLabel(nullLabel)
                mv.visitLabel(endLabel)
            }
        }
    }

    /**
     * Emits bytecode to read a List property field from [java.io.DataInput].
     *
     * Reads the list size (-1 means null list), then for each element reads a null marker
     * and the element value using the element descriptor.
     *
     * @param mv The method visitor.
     * @param internalName The JVM internal name of the class owning the field.
     * @param fieldName The field name (`prop_X`).
     * @param mapping The property field mapping with element type information.
     */
    private fun emitReadListField(
        mv: org.objectweb.asm.MethodVisitor,
        internalName: String,
        fieldName: String,
        mapping: PropertyFieldMapping
    ) {
        val nullLabel = Label()
        val endLabel = Label()
        val loopLabel = Label()
        val loopEndLabel = Label()
        val elemNullLabel = Label()
        val elemEndLabel = Label()

        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataInput", "readInt", "()I", true)
        mv.visitVarInsn(Opcodes.ISTORE, 3)
        mv.visitVarInsn(Opcodes.ILOAD, 3)
        mv.visitInsn(Opcodes.ICONST_M1)
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, nullLabel)

        mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList")
        mv.visitInsn(Opcodes.DUP)
        mv.visitVarInsn(Opcodes.ILOAD, 3)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "(I)V", false)
        mv.visitVarInsn(Opcodes.ASTORE, 4)

        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitVarInsn(Opcodes.ISTORE, 5)

        mv.visitLabel(loopLabel)
        mv.visitVarInsn(Opcodes.ILOAD, 5)
        mv.visitVarInsn(Opcodes.ILOAD, 3)
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, loopEndLabel)

        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataInput", "readByte", "()B", true)
        mv.visitJumpInsn(Opcodes.IFEQ, elemNullLabel)

        mv.visitVarInsn(Opcodes.ALOAD, 4)
        emitReadValue(mv, mapping.elementDescriptor, mapping.enumType)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true)
        mv.visitInsn(Opcodes.POP)
        mv.visitJumpInsn(Opcodes.GOTO, elemEndLabel)

        mv.visitLabel(elemNullLabel)
        mv.visitVarInsn(Opcodes.ALOAD, 4)
        mv.visitInsn(Opcodes.ACONST_NULL)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true)
        mv.visitInsn(Opcodes.POP)

        mv.visitLabel(elemEndLabel)
        mv.visitIincInsn(5, 1)
        mv.visitJumpInsn(Opcodes.GOTO, loopLabel)

        mv.visitLabel(loopEndLabel)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 4)
        mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, fieldName, "Ljava/util/List;")

        mv.visitJumpInsn(Opcodes.GOTO, endLabel)
        mv.visitLabel(nullLabel)
        mv.visitLabel(endLabel)
    }

    /**
     * Emits bytecode that reads a single value from `DataInput` (local 1) and pushes
     * it onto the operand stack as a boxed object reference.
     *
     * For enum types, calls `metamodel.resolveEnumValue(enumName, entry)` using the
     * [Metamodel] in local 2.
     *
     * @param mv The method visitor.
     * @param elementDescriptor The JVM element type descriptor.
     * @param enumType The metamodel enum name, or null if not an enum.
     */
    private fun emitReadValue(
        mv: org.objectweb.asm.MethodVisitor,
        elementDescriptor: String,
        enumType: String?
    ) {
        when (elementDescriptor) {
            "Ljava/lang/Integer;" -> {
                mv.visitVarInsn(Opcodes.ALOAD, 1)
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataInput", "readInt", "()I", true)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)
            }
            "Ljava/lang/Long;" -> {
                mv.visitVarInsn(Opcodes.ALOAD, 1)
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataInput", "readLong", "()J", true)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)
            }
            "Ljava/lang/Float;" -> {
                mv.visitVarInsn(Opcodes.ALOAD, 1)
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataInput", "readFloat", "()F", true)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false)
            }
            "Ljava/lang/Double;" -> {
                mv.visitVarInsn(Opcodes.ALOAD, 1)
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataInput", "readDouble", "()D", true)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false)
            }
            "Ljava/lang/Boolean;" -> {
                mv.visitVarInsn(Opcodes.ALOAD, 1)
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataInput", "readByte", "()B", true)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
            }
            "Ljava/lang/String;" -> {
                mv.visitVarInsn(Opcodes.ALOAD, 1)
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataInput", "readUTF", "()Ljava/lang/String;", true)
            }
            else -> {
                if (enumType != null) {
                    mv.visitVarInsn(Opcodes.ALOAD, 2)
                    mv.visitLdcInsn(enumType)
                    mv.visitVarInsn(Opcodes.ALOAD, 1)
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataInput", "readUTF", "()Ljava/lang/String;", true)
                    mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL, METAMODEL_CLASS, "resolveEnumValue",
                        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false
                    )
                } else {
                    mv.visitVarInsn(Opcodes.ALOAD, 1)
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataInput", "readUTF", "()Ljava/lang/String;", true)
                }
            }
        }
    }

    /**
     * Generates `writeLinkFields(DataOutput, Map)` which serializes all link (association)
     * Set fields to a [java.io.DataOutput] in field-index order.
     *
     * For each Set field, writes the count of elements followed by one int per element,
     * where the int is the element's sequential index looked up from [instanceIndex].
     *
     * @param cw The [ClassWriter] to emit into.
     * @param internalName The JVM internal name of the instance class.
     * @param allLinkMappings All link mappings (own + inherited).
     */
    private fun generateWriteLinkFields(
        cw: ClassWriter,
        internalName: String,
        allLinkMappings: Map<String, LinkFieldMapping>
    ) {
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC, "writeLinkFields",
            "(Ljava/io/DataOutput;Ljava/util/Map;)V", null, null
        )
        mv.visitCode()

        val sortedMappings = allLinkMappings.values.sortedBy { it.fieldIndex }
        for (mapping in sortedMappings) {
            val fieldName = "prop_${mapping.fieldIndex}"
            val nullLabel = Label()
            val endLabel = Label()
            val loopLabel = Label()
            val loopEndLabel = Label()

            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitFieldInsn(Opcodes.GETFIELD, internalName, fieldName, "Ljava/util/Set;")
            mv.visitVarInsn(Opcodes.ASTORE, 3)
            mv.visitVarInsn(Opcodes.ALOAD, 3)
            mv.visitJumpInsn(Opcodes.IFNULL, nullLabel)

            mv.visitVarInsn(Opcodes.ALOAD, 1)
            mv.visitVarInsn(Opcodes.ALOAD, 3)
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "size", "()I", true)
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataOutput", "writeInt", "(I)V", true)

            mv.visitVarInsn(Opcodes.ALOAD, 3)
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator", "()Ljava/util/Iterator;", true)
            mv.visitVarInsn(Opcodes.ASTORE, 4)

            mv.visitLabel(loopLabel)
            mv.visitVarInsn(Opcodes.ALOAD, 4)
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true)
            mv.visitJumpInsn(Opcodes.IFEQ, loopEndLabel)

            mv.visitVarInsn(Opcodes.ALOAD, 1)
            mv.visitVarInsn(Opcodes.ALOAD, 2)
            mv.visitVarInsn(Opcodes.ALOAD, 4)
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true)
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true)
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Integer")
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false)
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataOutput", "writeInt", "(I)V", true)
            mv.visitJumpInsn(Opcodes.GOTO, loopLabel)

            mv.visitLabel(loopEndLabel)
            mv.visitJumpInsn(Opcodes.GOTO, endLabel)

            mv.visitLabel(nullLabel)
            mv.visitVarInsn(Opcodes.ALOAD, 1)
            mv.visitInsn(Opcodes.ICONST_0)
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataOutput", "writeInt", "(I)V", true)

            mv.visitLabel(endLabel)
        }

        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * Generates `readLinkFields(DataInput, ModelInstance[])` which deserializes all link
     * (association) Set fields from a [java.io.DataInput] in field-index order.
     *
     * For each Set field, reads the count then creates a presized [java.util.HashSet]
     * and populates it by looking up each index in the [instances] array.
     *
     * @param cw The [ClassWriter] to emit into.
     * @param internalName The JVM internal name of the instance class.
     * @param allLinkMappings All link mappings (own + inherited).
     */
    private fun generateReadLinkFields(
        cw: ClassWriter,
        internalName: String,
        allLinkMappings: Map<String, LinkFieldMapping>
    ) {
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC, "readLinkFields",
            "(Ljava/io/DataInput;[L$MODEL_INSTANCE_CLASS;)V", null, null
        )
        mv.visitCode()

        val sortedMappings = allLinkMappings.values.sortedBy { it.fieldIndex }
        for (mapping in sortedMappings) {
            val fieldName = "prop_${mapping.fieldIndex}"
            val endLabel = Label()
            val loopLabel = Label()
            val loopEndLabel = Label()

            mv.visitVarInsn(Opcodes.ALOAD, 1)
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataInput", "readInt", "()I", true)
            mv.visitVarInsn(Opcodes.ISTORE, 3)

            mv.visitVarInsn(Opcodes.ILOAD, 3)
            mv.visitJumpInsn(Opcodes.IFEQ, endLabel)

            mv.visitTypeInsn(Opcodes.NEW, "java/util/HashSet")
            mv.visitInsn(Opcodes.DUP)
            mv.visitVarInsn(Opcodes.ILOAD, 3)
            mv.visitInsn(Opcodes.DUP)
            mv.visitInsn(Opcodes.ICONST_1)
            mv.visitInsn(Opcodes.ISHR)
            mv.visitInsn(Opcodes.IADD)
            mv.visitInsn(Opcodes.ICONST_1)
            mv.visitInsn(Opcodes.IADD)
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashSet", "<init>", "(I)V", false)
            mv.visitVarInsn(Opcodes.ASTORE, 4)

            mv.visitInsn(Opcodes.ICONST_0)
            mv.visitVarInsn(Opcodes.ISTORE, 5)

            mv.visitLabel(loopLabel)
            mv.visitVarInsn(Opcodes.ILOAD, 5)
            mv.visitVarInsn(Opcodes.ILOAD, 3)
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, loopEndLabel)

            mv.visitVarInsn(Opcodes.ALOAD, 4)
            mv.visitVarInsn(Opcodes.ALOAD, 2)
            mv.visitVarInsn(Opcodes.ALOAD, 1)
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/io/DataInput", "readInt", "()I", true)
            mv.visitInsn(Opcodes.AALOAD)
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z", true)
            mv.visitInsn(Opcodes.POP)

            mv.visitIincInsn(5, 1)
            mv.visitJumpInsn(Opcodes.GOTO, loopLabel)

            mv.visitLabel(loopEndLabel)
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitVarInsn(Opcodes.ALOAD, 4)
            mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, fieldName, "Ljava/util/Set;")

            mv.visitLabel(endLabel)
        }

        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * Builds the class hierarchy map for all classes in the metamodel.
     *
     * Each entry maps a class name to the set of all direct and indirect subtypes of that
     * class, including itself. This is used by [Model] to support [Model.getAllInstances] with
     * subtype polymorphism.
     *
     * @return Map from base class name to the set of all subtype names (including self).
     */
    private fun buildClassHierarchy(): Map<String, Set<String>> {
        val result = mutableMapOf<String, MutableSet<String>>()
        for (classData in data.classes) {
            result.getOrPut(classData.name) { mutableSetOf() }.add(classData.name)
        }
        for (classData in data.classes) {
            var current = classData
            while (current.extends.isNotEmpty()) {
                val parentName = current.extends.first()
                result.getOrPut(parentName) { mutableSetOf() }.add(classData.name)
                current = classMap[parentName] ?: break
            }
        }
        return result
    }
}
