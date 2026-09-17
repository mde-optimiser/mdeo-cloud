package com.mdeo.script.compiler

import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.ValueType
import com.mdeo.script.compiler.registry.type.MethodDefinition
import com.mdeo.script.compiler.registry.type.PropertyDefinition
import com.mdeo.script.compiler.registry.type.TypeDefinitionImpl
import com.mdeo.script.compiler.util.ASMUtil
import com.mdeo.script.compiler.util.CoercionUtil
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * The JVM classes of records and their members, shared by the records scripts declare and the
 * records contributions define.
 *
 * Every record is a final class extending [com.mdeo.script.runtime.ScriptRecord] that keeps its
 * fields boxed in declaration order. Fields are read and written by position, and the copy method
 * [RECORD_COPY_METHOD] is compiled at the call site.
 */
internal object RecordClasses {

    /**
     * Internal name of the base class of every record.
     */
    const val RECORD_BASE = "com/mdeo/script/runtime/ScriptRecord"

    /**
     * Name of the method that copies a record with some fields changed.
     */
    const val RECORD_COPY_METHOD = "with"

    private const val FIELD_NAMES = "FIELD_NAMES"

    /**
     * Generates the class of a record.
     *
     * It has a constructor taking the field values as an `Object[]`, and implements `copy()`.
     *
     * @param jvmClassName Internal name of the class
     * @param typeId The type the class stands for, `<package>.<name>`
     * @param fieldNames The field names, in declaration order
     * @return The bytecode
     */
    fun generate(jvmClassName: String, typeId: String, fieldNames: List<String>): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            jvmClassName,
            null,
            RECORD_BASE,
            null
        )
        cw.visitField(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            FIELD_NAMES,
            "[Ljava/lang/String;",
            null,
            null
        ).visitEnd()

        val clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        clinit.visitCode()
        clinit.visitLdcInsn(fieldNames.size)
        clinit.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
        fieldNames.forEachIndexed { index, fieldName ->
            clinit.visitInsn(Opcodes.DUP)
            clinit.visitLdcInsn(index)
            clinit.visitLdcInsn(fieldName)
            clinit.visitInsn(Opcodes.AASTORE)
        }
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, jvmClassName, FIELD_NAMES, "[Ljava/lang/String;")
        clinit.visitInsn(Opcodes.RETURN)
        clinit.visitMaxs(0, 0)
        clinit.visitEnd()

        val init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "([Ljava/lang/Object;)V", null, null)
        init.visitCode()
        init.visitVarInsn(Opcodes.ALOAD, 0)
        init.visitLdcInsn(typeId)
        init.visitFieldInsn(Opcodes.GETSTATIC, jvmClassName, FIELD_NAMES, "[Ljava/lang/String;")
        init.visitVarInsn(Opcodes.ALOAD, 1)
        init.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            RECORD_BASE,
            "<init>",
            "(Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/Object;)V",
            false
        )
        init.visitInsn(Opcodes.RETURN)
        init.visitMaxs(0, 0)
        init.visitEnd()

        val copy = cw.visitMethod(Opcodes.ACC_PUBLIC, "copy", "()L$RECORD_BASE;", null, null)
        copy.visitCode()
        copy.visitTypeInsn(Opcodes.NEW, jvmClassName)
        copy.visitInsn(Opcodes.DUP)
        copy.visitVarInsn(Opcodes.ALOAD, 0)
        copy.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RECORD_BASE, "copyValues", "()[Ljava/lang/Object;", false)
        copy.visitMethodInsn(Opcodes.INVOKESPECIAL, jvmClassName, "<init>", "([Ljava/lang/Object;)V", false)
        copy.visitInsn(Opcodes.ARETURN)
        copy.visitMaxs(0, 0)
        copy.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }

    /**
     * Adds the fields of a record as properties, and its copy method, to its type definition.
     *
     * @param definition The type definition of the record
     * @param fieldNames The field names, in declaration order
     * @param fieldTypes The field types, in declaration order
     */
    fun addMembers(definition: TypeDefinitionImpl, fieldNames: List<String>, fieldTypes: List<ReturnType>) {
        fieldNames.forEachIndexed { index, fieldName ->
            definition.addProperty(FieldPropertyDefinition(fieldName, index, fieldTypes[index]))
        }
        definition.addMethod(
            CopyMethodDefinition(
                fieldTypes.map { it as ValueType },
                ClassTypeRef(definition.typePackage, definition.typeName, false)
            )
        )
    }

    /**
     * Emits the construction of a record from its field values.
     *
     * The field values must be in the local variable slots given, with the JVM types of the field
     * types. Leaves the new record on the stack.
     *
     * @param mv The method visitor
     * @param jvmClassName Internal name of the record class
     * @param fieldTypes The field types, in declaration order
     * @param slots The local variable slot of each field value
     */
    fun emitConstruction(mv: MethodVisitor, jvmClassName: String, fieldTypes: List<ReturnType>, slots: List<Int>) {
        mv.visitTypeInsn(Opcodes.NEW, jvmClassName)
        mv.visitInsn(Opcodes.DUP)
        mv.visitLdcInsn(fieldTypes.size)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
        fieldTypes.forEachIndexed { index, fieldType ->
            mv.visitInsn(Opcodes.DUP)
            mv.visitLdcInsn(index)
            mv.visitVarInsn(ASMUtil.getLoadOpcode(fieldType), slots[index])
            emitBoxing(fieldType, mv)
            mv.visitInsn(Opcodes.AASTORE)
        }
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, jvmClassName, "<init>", "([Ljava/lang/Object;)V", false)
    }

    /**
     * Boxes a value of a non-nullable primitive type, which is how a record holds it.
     *
     * @param type The type of the value on the stack
     * @param mv The method visitor
     */
    fun emitBoxing(type: ReturnType, mv: MethodVisitor) {
        if (type is ClassTypeRef && !type.isNullable && CoercionUtil.isPrimitiveType(type)) {
            CoercionUtil.emitBoxing(type, mv)
        }
    }

    /**
     * A record field, read and written by position and converted to its declared type.
     */
    private class FieldPropertyDefinition(
        override val name: String,
        private val index: Int,
        private val type: ReturnType
    ) : PropertyDefinition {
        override val ownerClass: String = RECORD_BASE
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
            emitBoxing(type, mv)
            mv.visitLdcInsn(index)
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RECORD_BASE, "set", "(Ljava/lang/Object;Ljava/lang/Object;I)V", false)
        }
    }

    /**
     * The copy method of a record, taking every field as an optional parameter.
     *
     * It has no JVM method of its own: the member call compiler copies the record and sets the
     * fields the call passes, see [com.mdeo.script.compiler.expressions.MemberCallCompiler].
     *
     * @param parameterTypes The field types, in declaration order
     * @param returnType The record type
     */
    class CopyMethodDefinition(
        override val parameterTypes: List<ValueType>,
        override val returnType: ReturnType
    ) : MethodDefinition {
        override val name: String = RECORD_COPY_METHOD
        override val overloadKey: String = ""
        override val descriptor: String = "()L$RECORD_BASE;"
        override val isStatic: Boolean = false
        override val ownerClass: String = RECORD_BASE
        override val isInterface: Boolean = false
        override val jvmMethodName: String = "copy"

        override fun emitInvocation(mv: MethodVisitor) {
            throw UnsupportedOperationException("The copy method of a record is compiled at the call site")
        }
    }
}
