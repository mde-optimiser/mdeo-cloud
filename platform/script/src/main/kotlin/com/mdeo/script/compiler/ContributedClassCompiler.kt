package com.mdeo.script.compiler

import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.script.ast.TypedPluginAst
import com.mdeo.script.ast.TypedPluginClass
import com.mdeo.script.compiler.registry.type.PropertyDefinition
import com.mdeo.script.compiler.registry.type.TypeDefinitionImpl
import com.mdeo.script.compiler.registry.type.TypeRegistry
import com.mdeo.script.compiler.util.ASMUtil
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Everything the runtime needs to know about one class a contribution defines.
 *
 * @param contribution Id of the contribution that defines it
 * @param name The class name
 * @param kind [TypedPluginClass.KIND_RECORD] or [TypedPluginClass.KIND_OPAQUE]
 * @param jvmClassName Internal name of the generated class
 * @param fieldNames The record's field names, in declaration order
 * @param fieldTypes The record's field types, in declaration order
 */
data class ContributedClassSpec(
    val contribution: String,
    val name: String,
    val kind: String,
    val jvmClassName: String,
    val fieldNames: List<String> = emptyList(),
    val fieldTypes: List<ReturnType> = emptyList()
) {
    /**
     * The type the script refers to the class by, `contrib/<contribution>.<name>`.
     */
    val typeId: String get() = "${TypedPluginClass.PACKAGE_PREFIX}/$contribution.$name"
}

/**
 * Generates the JVM classes of contributed records and opaque classes, and makes them known to
 * the compiler.
 *
 * Each gets a final class of its own, extending [com.mdeo.script.runtime.ScriptRecord] or
 * [com.mdeo.script.runtime.ScriptOpaque], so a script's `is` and `as` tell them apart. A record's
 * fields are readonly properties that read the field by position; an opaque class has no members.
 */
internal object ContributedClassCompiler {

    private const val RECORD_BASE = "com/mdeo/script/runtime/ScriptRecord"
    private const val OPAQUE_BASE = "com/mdeo/script/runtime/ScriptOpaque"

    /**
     * Builds the specs of every class a plugin AST defines.
     *
     * @param pluginAst The plugin AST
     * @return The specs, keyed by [ContributedClassSpec.typeId]
     */
    fun specs(pluginAst: TypedPluginAst?): Map<String, ContributedClassSpec> =
        pluginAst?.classes.orEmpty().associate { contributed ->
            val spec = ContributedClassSpec(
                contribution = contributed.contribution,
                name = contributed.name,
                kind = contributed.kind,
                jvmClassName = jvmClassName(contributed.contribution, contributed.name),
                fieldNames = contributed.fields.map { it.name },
                fieldTypes = contributed.fields.map { pluginAst!!.types[it.type] }
            )
            spec.typeId to spec
        }

    /**
     * Generates the bytecode of every class.
     *
     * @param specs The classes
     * @return Bytecode keyed by JVM binary class name
     */
    fun generate(specs: Collection<ContributedClassSpec>): Map<String, ByteArray> =
        specs.associate { spec -> spec.jvmClassName.replace('/', '.') to generate(spec) }

    /**
     * Registers every class in a registry chained to [parent].
     *
     * @param parent The registry scripts see otherwise
     * @param specs The classes
     * @return The registry that also knows the contributed classes, or [parent] when there are none
     */
    fun register(parent: TypeRegistry, specs: Collection<ContributedClassSpec>): TypeRegistry {
        if (specs.isEmpty()) return parent
        val registry = TypeRegistry(parent = parent)
        for (spec in specs) {
            val definition = TypeDefinitionImpl(
                typePackage = "${TypedPluginClass.PACKAGE_PREFIX}/${spec.contribution}",
                typeName = spec.name,
                extends = listOf(ClassTypeRef("builtin", "Any", false)),
                jvmClassName = spec.jvmClassName
            )
            spec.fieldNames.forEachIndexed { index, fieldName ->
                definition.addProperty(RecordFieldPropertyDefinition(fieldName, index, spec.fieldTypes[index], spec.jvmClassName))
            }
            registry.register(definition)
        }
        return registry
    }

    private fun jvmClassName(contribution: String, name: String): String =
        "com/mdeo/script/contrib/${packageSegment(contribution)}/$name"

    /**
     * Turns a contribution id into a package name segment, differently for every id: `a-b`, `a.b`
     * and `a_b` are three contributions, and their classes must not land in one package.
     */
    internal fun packageSegment(contribution: String): String = buildString {
        for (char in contribution) {
            when {
                char == '_' -> append("__")
                char == '-' -> append("_d")
                char == '.' -> append("_p")
                char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' -> append(char)
                else -> append("_u").append(char.code.toString(16).padStart(4, '0'))
            }
        }
    }

    private fun generate(spec: ContributedClassSpec): ByteArray {
        val isRecord = spec.kind == TypedPluginClass.KIND_RECORD
        val base = if (isRecord) RECORD_BASE else OPAQUE_BASE
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER, spec.jvmClassName, null, base, null)

        val descriptor = if (isRecord) "([Ljava/lang/Object;)V" else "(J)V"
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", descriptor, null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitLdcInsn(spec.typeId)
        if (isRecord) {
            mv.visitVarInsn(Opcodes.ALOAD, 1)
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, base, "<init>", "(Ljava/lang/String;[Ljava/lang/Object;)V", false)
        } else {
            mv.visitVarInsn(Opcodes.LLOAD, 1)
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, base, "<init>", "(Ljava/lang/String;J)V", false)
        }
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }

    /**
     * A record field, read by position and converted to its declared type.
     */
    private class RecordFieldPropertyDefinition(
        override val name: String,
        private val index: Int,
        private val type: ReturnType,
        override val ownerClass: String
    ) : PropertyDefinition {
        override val descriptor: String = ASMUtil.getTypeDescriptor(type)
        override val isStatic: Boolean = false
        override val isInterface: Boolean = false
        override val getterName: String = "field"

        override fun emitAccess(mv: MethodVisitor) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, RECORD_BASE)
            mv.visitLdcInsn(index)
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RECORD_BASE, "field", "(I)Ljava/lang/Object;", false)
            ASMUtil.emitUnboxOrCast(type, mv)
        }

        override fun emitSet(mv: MethodVisitor, valueDescriptor: String) {
            throw UnsupportedOperationException("Field '$name' of a record is read-only")
        }
    }
}
