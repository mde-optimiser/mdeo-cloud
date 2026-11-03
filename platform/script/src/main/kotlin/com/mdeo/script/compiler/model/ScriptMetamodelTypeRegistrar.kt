package com.mdeo.script.compiler.model

import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.MetamodelMetadata
import com.mdeo.metamodel.data.ClassData
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.metamodel.data.EnumData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.PropertyData
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.ValueType
import com.mdeo.script.compiler.registry.property.GlobalPropertyRegistry
import com.mdeo.script.compiler.registry.property.StaticGlobalPropertyDefinition
import com.mdeo.script.compiler.registry.type.FieldGetterPropertyDefinition
import com.mdeo.script.compiler.registry.type.MethodDefinition
import com.mdeo.script.compiler.registry.type.PropertyDefinition
import com.mdeo.script.compiler.registry.type.TypeDefinitionImpl
import com.mdeo.script.compiler.registry.type.TypeRegistry
import com.mdeo.script.compiler.ScriptCompiler
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Registers metamodel types into a TypeRegistry for script compilation.
 *
 * Uses a compiled [Metamodel] to look up generated class names (instance classes,
 * enum value/container classes, class container classes) and field mappings (`prop_X`).
 *
 * Property access emits GETFIELD instructions via [DirectFieldPropertyDefinition]
 * instead of delegating to a backing interface.
 */
object ScriptMetamodelTypeRegistrar {

    const val ENUM_CONTAINER_PACKAGE = "enum-container"
    const val ENUM_PACKAGE = "enum"
    const val CLASS_PACKAGE = "class"
    const val CLASS_CONTAINER_PACKAGE = "class-container"

    data class MetamodelCompilationResult(
        val typeRegistry: TypeRegistry,
        val fileScopeRegistry: GlobalPropertyRegistry
    )

    /**
     * Creates a type registry and file-scope property registry enriched with metamodel types.
     *
     * The types of a class or enum are named after the metamodel file declaring it
     * ([MetamodelData.pathOf]), which is a file the metamodel imports for some of them, as in the
     * script language's type system.
     *
     * @param metamodel The compiled metamodel.
     */
    fun createRegistry(metamodel: Metamodel): MetamodelCompilationResult {
        val registry = TypeRegistry(parent = TypeRegistry.GLOBAL)
        val metamodelData = metamodel.data
        val metadata = metamodel.metadata

        for (enumData in metamodelData.enums) {
            val enumPath = metamodelData.pathOf(enumData)
            registerEnumContainer(registry, enumData, enumPath)
            registerEnumValueType(registry, enumData, enumPath)
        }

        val classMap = metamodelData.classes.associateBy { it.name }
        for (classData in metamodelData.classes) {
            val classPath = metamodelData.pathOf(classData)
            registerClassType(registry, classData, classMap, metamodelData, metadata)
            registerClassContainerType(registry, classData, classPath)
        }

        val fileScopeRegistry = buildFileScopeRegistry(metamodelData)

        return MetamodelCompilationResult(registry, fileScopeRegistry)
    }

    private fun buildFileScopeRegistry(metamodelData: MetamodelData): GlobalPropertyRegistry {
        val registry = GlobalPropertyRegistry()

        for (classData in metamodelData.classes) {
            val containerClass = Metamodel.getClassContainerClassName(classData.name)
            registry.registerProperty(
                StaticGlobalPropertyDefinition(
                    name = classData.name,
                    descriptor = "L$containerClass;",
                    ownerClass = containerClass,
                    getterName = "INSTANCE"
                )
            )
        }

        for (enumData in metamodelData.enums) {
            val containerClass = Metamodel.getEnumContainerClassName(enumData.name)
            registry.registerProperty(
                StaticGlobalPropertyDefinition(
                    name = enumData.name,
                    descriptor = "L$containerClass;",
                    ownerClass = containerClass,
                    getterName = "INSTANCE"
                )
            )
        }

        return registry
    }

    private fun registerClassContainerType(
        registry: TypeRegistry,
        classData: ClassData,
        metamodelPath: String
    ) {
        val typePackage = "$CLASS_CONTAINER_PACKAGE$metamodelPath"
        val containerClassName = Metamodel.getClassContainerClassName(classData.name)
        val instanceClassName = Metamodel.getInstanceClassName(classData.name)

        val typeDef = TypeDefinitionImpl(
            typePackage = typePackage,
            typeName = classData.name,
            jvmClassName = containerClassName
        )

        typeDef.addMethod(ClassContainerAllMethodDefinition(
            className = classData.name,
            containerClassName = containerClassName,
            instanceClassName = instanceClassName,
            metamodelPath = metamodelPath
        ))
        typeDef.addMethod(ClassContainerFirstMethodDefinition(
            className = classData.name,
            containerClassName = containerClassName,
            instanceClassName = instanceClassName,
            metamodelPath = metamodelPath
        ))
        typeDef.addMethod(ClassContainerFirstOrNullMethodDefinition(
            className = classData.name,
            containerClassName = containerClassName,
            instanceClassName = instanceClassName,
            metamodelPath = metamodelPath
        ))

        registry.register(typeDef)
    }

    private fun registerEnumContainer(
        registry: TypeRegistry,
        enumData: EnumData,
        metamodelPath: String
    ) {
        val typePackage = "$ENUM_CONTAINER_PACKAGE$metamodelPath"
        val containerClass = Metamodel.getEnumContainerClassName(enumData.name)
        val valueClass = Metamodel.getEnumValueClassName(enumData.name)

        val typeDef = TypeDefinitionImpl(
            typePackage = typePackage,
            typeName = enumData.name,
            jvmClassName = containerClass
        )

        for (entry in enumData.entries) {
            typeDef.addProperty(EnumEntryPropertyDefinition(
                name = entry,
                containerClass = containerClass,
                valueClass = valueClass
            ))
        }

        registry.register(typeDef)
    }

    private fun registerEnumValueType(
        registry: TypeRegistry,
        enumData: EnumData,
        metamodelPath: String
    ) {
        val typePackage = "$ENUM_PACKAGE$metamodelPath"
        val valueClassName = Metamodel.getEnumValueClassName(enumData.name)
        val typeDef = TypeDefinitionImpl(
            typePackage = typePackage,
            typeName = enumData.name,
            extends = listOf(ClassTypeRef(`package` = "builtin", type = "Any", isNullable = false)),
            jvmClassName = valueClassName
        )
        typeDef.addMethod(EnumValueGetEntryMethodDefinition(valueClassName))
        registry.register(typeDef)
    }

    private fun registerClassType(
        registry: TypeRegistry,
        classData: ClassData,
        classMap: Map<String, ClassData>,
        metamodelData: MetamodelData,
        metadata: MetamodelMetadata
    ) {
        val typePackage = "$CLASS_PACKAGE${metamodelData.pathOf(classData)}"
        // The type interface, not the instance class: an instance of a class inheriting through a
        // second superclass implements the interface but does not extend the class.
        val typeInterfaceName = Metamodel.getInstanceInterfaceName(classData.name)
        val classMeta = metadata.classes[classData.name]

        val extendsRefs = classData.extends.map { superName ->
            val superPath = classMap[superName]?.let { metamodelData.pathOf(it) } ?: metamodelData.path
            ClassTypeRef(`package` = "$CLASS_PACKAGE$superPath", type = superName, isNullable = false)
        }.let { refs ->
            if (refs.isEmpty()) listOf(ClassTypeRef(`package` = "builtin", type = "Any", isNullable = false))
            else refs
        }

        val typeDef = TypeDefinitionImpl(
            typePackage = typePackage,
            typeName = classData.name,
            extends = extendsRefs,
            jvmClassName = typeInterfaceName
        )

        for (property in classData.properties) {
            val fieldMapping = classMeta?.propertyFields?.get(property.name)
            if (fieldMapping != null) {
                typeDef.addProperty(FieldGetterPropertyDefinition(
                    name = property.name,
                    descriptor = fieldMapping.fieldDescriptor,
                    ownerClass = typeInterfaceName,
                    getterName = Metamodel.getFieldGetterName(fieldMapping.fieldIndex),
                    fieldDescriptor = fieldMapping.fieldDescriptor,
                    upper = fieldMapping.upper
                ))
            }
        }

        if (classMeta != null) {
            for ((roleName, linkMapping) in classMeta.linkFields) {
                val isOwnLink = isOwnLink(classData.name, roleName, metamodelData)
                if (isOwnLink) {
                    val scriptLinkDescriptor = if (linkMapping.upper == 1) {
                        val targetInternalName = Metamodel.getInstanceInterfaceName(linkMapping.oppositeClassName)
                        "L$targetInternalName;"
                    } else {
                        "Ljava/util/Set;"
                    }
                    typeDef.addProperty(FieldGetterPropertyDefinition(
                        name = roleName,
                        descriptor = scriptLinkDescriptor,
                        ownerClass = typeInterfaceName,
                        getterName = Metamodel.getFieldGetterName(linkMapping.fieldIndex),
                        fieldDescriptor = linkMapping.fieldDescriptor,
                        upper = linkMapping.upper
                    ))
                }
            }
        }

        registry.register(typeDef)
    }

    private fun isOwnLink(
        className: String,
        roleName: String,
        metamodelData: MetamodelData
    ): Boolean {
        for (assoc in metamodelData.associations) {
            if (assoc.source.className == className && assoc.source.name == roleName) return true
            if (assoc.target.className == className && assoc.target.name == roleName) return true
        }
        return false
    }
}

private class EnumEntryPropertyDefinition(
    override val name: String,
    private val containerClass: String,
    private val valueClass: String
) : PropertyDefinition {

    override val descriptor: String = "L$valueClass;"
    override val isStatic: Boolean = false
    override val ownerClass: String = containerClass
    override val isInterface: Boolean = false
    override val getterName: String = name

    override fun emitAccess(mv: MethodVisitor) {
        mv.visitInsn(Opcodes.POP)
        mv.visitFieldInsn(
            Opcodes.GETSTATIC,
            containerClass,
            name,
            "L$valueClass;"
        )
    }

    override fun emitSet(mv: MethodVisitor, valueDescriptor: String) {
        throw UnsupportedOperationException("Enum entry '$name' is read-only")
    }
}

private class ClassContainerAllMethodDefinition(
    private val className: String,
    private val containerClassName: String,
    private val instanceClassName: String,
    private val metamodelPath: String
) : MethodDefinition {

    override val overloadKey: String = ""
    override val name: String = "all"
    override val descriptor: String = "()Ljava/util/List;"
    override val isStatic: Boolean = false
    override val ownerClass: String = containerClassName
    override val isInterface: Boolean = false
    override val jvmMethodName: String = "all"
    override val isVarArgs: Boolean = false
    override val parameterTypes: List<ValueType> = emptyList()
    override val returnType: ReturnType =
        ClassTypeRef(`package` = "builtin", type = "Collection", isNullable = false)

    override val requiresContext: Boolean
        get() = true

    override fun emitInvocation(mv: MethodVisitor) {
        mv.visitInsn(Opcodes.SWAP)
        mv.visitInsn(Opcodes.POP)

        mv.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            ScriptCompiler.CONTEXT_INTERNAL_NAME,
            "getModel",
            "()Lcom/mdeo/metamodel/Model;",
            true
        )
        mv.visitLdcInsn(className)
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "com/mdeo/metamodel/Model",
            "getAllInstances",
            "(Ljava/lang/String;)Ljava/util/List;",
            false
        )

        mv.visitTypeInsn(Opcodes.NEW, "com/mdeo/script/stdlib/impl/collections/BagImpl")
        mv.visitInsn(Opcodes.DUP_X1)
        mv.visitInsn(Opcodes.SWAP)
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "com/mdeo/script/stdlib/impl/collections/BagImpl",
            "<init>",
            "(Ljava/util/Collection;)V",
            false
        )
    }
}

private class ClassContainerFirstMethodDefinition(
    private val className: String,
    private val containerClassName: String,
    private val instanceClassName: String,
    private val metamodelPath: String
) : MethodDefinition {

    override val overloadKey: String = ""
    override val name: String = "first"
    override val descriptor: String = "()Ljava/lang/Object;"
    override val isStatic: Boolean = false
    override val ownerClass: String = containerClassName
    override val isInterface: Boolean = false
    override val jvmMethodName: String = "first"
    override val isVarArgs: Boolean = false
    override val parameterTypes: List<ValueType> = emptyList()
    override val returnType: ReturnType =
        ClassTypeRef(`package` = "${ScriptMetamodelTypeRegistrar.CLASS_PACKAGE}$metamodelPath", type = className, isNullable = false)

    override val requiresContext: Boolean
        get() = true

    override fun emitInvocation(mv: MethodVisitor) {
        mv.visitInsn(Opcodes.SWAP)
        mv.visitInsn(Opcodes.POP)

        mv.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            ScriptCompiler.CONTEXT_INTERNAL_NAME,
            "getModel",
            "()Lcom/mdeo/metamodel/Model;",
            true
        )
        mv.visitLdcInsn(className)
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "com/mdeo/metamodel/Model",
            "getAllInstances",
            "(Ljava/lang/String;)Ljava/util/List;",
            false
        )

        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true)
    }
}

private class ClassContainerFirstOrNullMethodDefinition(
    private val className: String,
    private val containerClassName: String,
    private val instanceClassName: String,
    private val metamodelPath: String
) : MethodDefinition {

    override val overloadKey: String = ""
    override val name: String = "firstOrNull"
    override val descriptor: String = "()Ljava/lang/Object;"
    override val isStatic: Boolean = false
    override val ownerClass: String = containerClassName
    override val isInterface: Boolean = false
    override val jvmMethodName: String = "firstOrNull"
    override val isVarArgs: Boolean = false
    override val parameterTypes: List<ValueType> = emptyList()
    override val returnType: ReturnType =
        ClassTypeRef(`package` = "${ScriptMetamodelTypeRegistrar.CLASS_PACKAGE}$metamodelPath", type = className, isNullable = true)

    override val requiresContext: Boolean
        get() = true

    override fun emitInvocation(mv: MethodVisitor) {
        mv.visitInsn(Opcodes.SWAP)
        mv.visitInsn(Opcodes.POP)

        mv.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            ScriptCompiler.CONTEXT_INTERNAL_NAME,
            "getModel",
            "()Lcom/mdeo/metamodel/Model;",
            true
        )
        mv.visitLdcInsn(className)
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "com/mdeo/metamodel/Model",
            "getAllInstances",
            "(Ljava/lang/String;)Ljava/util/List;",
            false
        )

        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z", true)

        val emptyLabel = Label()
        val endLabel = Label()
        mv.visitJumpInsn(Opcodes.IFNE, emptyLabel)

        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true)
        mv.visitJumpInsn(Opcodes.GOTO, endLabel)

        mv.visitLabel(emptyLabel)
        mv.visitInsn(Opcodes.POP)
        mv.visitInsn(Opcodes.ACONST_NULL)

        mv.visitLabel(endLabel)
    }
}

private class EnumValueGetEntryMethodDefinition(
    private val valueClassName: String
) : MethodDefinition {

    override val overloadKey: String = ""
    override val name: String = "getEntry"
    override val descriptor: String = "()Ljava/lang/String;"
    override val isStatic: Boolean = false
    override val ownerClass: String = valueClassName
    override val isInterface: Boolean = false
    override val jvmMethodName: String = "getEntry"
    override val isVarArgs: Boolean = false
    override val parameterTypes: List<ValueType> = emptyList()
    override val returnType: ReturnType =
        ClassTypeRef(`package` = "builtin", type = "string", isNullable = false)

    override fun emitInvocation(mv: MethodVisitor) {
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            valueClassName,
            "getEntry",
            "()Ljava/lang/String;",
            false
        )
    }
}
