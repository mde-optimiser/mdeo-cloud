package com.mdeo.script.compiler.util

import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.VoidType
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Centralized utility for ASM/JVM bytecode operations.
 * 
 * This object consolidates commonly duplicated functions across the compiler:
 * - Type descriptor generation
 * - Load/Store opcode selection
 * - Stack size calculation
 * - Unboxing and casting operations
 * 
 * All compilers should use these utilities instead of implementing their own versions.
 */
object ASMUtil {
    
    /**
     * Gets the JVM type descriptor for a return type.
     * 
     * This is the canonical method for getting type descriptors.
     * 
     * @param returnType The return type to get the descriptor for.
     * @return The JVM type descriptor string (e.g., "I" for int, "Ljava/lang/String;" for String).
     */
    fun getTypeDescriptor(returnType: ReturnType): String {
        return when (returnType) {
            is VoidType -> "V"
            is ClassTypeRef -> getClassTypeDescriptor(returnType)
            else -> "Ljava/lang/Object;"
        }
    }
    
    /**
     * Gets the JVM type descriptor for a class type reference.
     * 
     * @param classType The class type reference.
     * @return The JVM type descriptor string.
     */
    private fun getClassTypeDescriptor(classType: ClassTypeRef): String {
        if (classType.`package` == "builtin") {
            return when (classType.type) {
                "int" -> if (classType.isNullable) "Ljava/lang/Integer;" else "I"
                "long" -> if (classType.isNullable) "Ljava/lang/Long;" else "J"
                "float" -> if (classType.isNullable) "Ljava/lang/Float;" else "F"
                "double" -> if (classType.isNullable) "Ljava/lang/Double;" else "D"
                "boolean" -> if (classType.isNullable) "Ljava/lang/Boolean;" else "Z"
                "string" -> "Ljava/lang/String;"
                else -> "Ljava/lang/Object;"
            }
        }
        return "Ljava/lang/Object;"
    }
    
    /**
     * Gets the primitive type descriptor for JVM.
     *
     * @param typeRef The ClassTypeRef of the primitive type.
     * @return The JVM type descriptor (e.g., "I" for int), or null if not a primitive type.
     */
    fun getPrimitiveDescriptor(typeRef: ClassTypeRef): String? {
        if (typeRef.`package` != "builtin") return null
        return when (typeRef.type) {
            "int" -> "I"
            "long" -> "J"
            "float" -> "F"
            "double" -> "D"
            "boolean" -> "Z"
            else -> null
        }
    }
    
    /**
     * Gets the appropriate LOAD opcode for a type.
     * 
     * Returns the JVM load instruction opcode based on the type:
     * - ILOAD for int and boolean primitives
     * - LLOAD for long primitives
     * - FLOAD for float primitives
     * - DLOAD for double primitives
     * - ALOAD for all reference types (including nullable primitives)
     * 
     * @param type The type being loaded.
     * @return The JVM LOAD opcode (ILOAD, LLOAD, FLOAD, DLOAD, or ALOAD).
     */
    fun getLoadOpcode(type: ReturnType): Int {
        if (type is ClassTypeRef) {
            if (type.isNullable) {
                return Opcodes.ALOAD
            }
            if (type.`package` == "builtin") {
                return when (type.type) {
                    "int", "boolean" -> Opcodes.ILOAD
                    "long" -> Opcodes.LLOAD
                    "float" -> Opcodes.FLOAD
                    "double" -> Opcodes.DLOAD
                    else -> Opcodes.ALOAD
                }
            }
        }
        return Opcodes.ALOAD
    }
    
    /**
     * Gets the appropriate STORE opcode for a type.
     * 
     * Returns the JVM store instruction opcode based on the type:
     * - ISTORE for int and boolean primitives
     * - LSTORE for long primitives
     * - FSTORE for float primitives
     * - DSTORE for double primitives
     * - ASTORE for all reference types (including nullable primitives)
     * 
     * @param type The type to get the store opcode for.
     * @return The appropriate JVM STORE opcode.
     */
    fun getStoreOpcode(type: ReturnType): Int {
        if (type is ClassTypeRef) {
            if (type.isNullable) {
                return Opcodes.ASTORE
            }
            if (type.`package` == "builtin") {
                return when (type.type) {
                    "int", "boolean" -> Opcodes.ISTORE
                    "long" -> Opcodes.LSTORE
                    "float" -> Opcodes.FSTORE
                    "double" -> Opcodes.DSTORE
                    else -> Opcodes.ASTORE
                }
            }
        }
        return Opcodes.ASTORE
    }

    /**
     * Pushes the zero value of a type: `0` or `false` for a primitive, `null` for a reference.
     *
     * @param type The type.
     * @param mv The method visitor to emit bytecode to.
     */
    fun emitZeroValue(type: ReturnType, mv: MethodVisitor) {
        when (getTypeDescriptor(type)) {
            "I", "Z", "B", "S", "C" -> mv.visitInsn(Opcodes.ICONST_0)
            "J" -> mv.visitInsn(Opcodes.LCONST_0)
            "F" -> mv.visitInsn(Opcodes.FCONST_0)
            "D" -> mv.visitInsn(Opcodes.DCONST_0)
            else -> mv.visitInsn(Opcodes.ACONST_NULL)
        }
    }
    
    /**
     * Gets the stack size for a ReturnType.
     * Objects (nullable types) are always 1 slot.
     * 
     * @param type The return type to check.
     * @return The number of stack slots (1 for most types, 2 for long/double primitives).
     */
    fun getStackSize(type: ReturnType): Int {
        if (type !is ClassTypeRef) {
            return 1
        }
        if (type.isNullable) {
            return 1
        }
        if (type.`package` == "builtin") {
            return when (type.type) {
                "long", "double" -> 2
                else -> 1
            }
        }
        return 1
    }
    
    /**
     * Gets the number of local variable slots required for a type.
     * 
     * @param type The return type to check.
     * @param isWrapped Whether the variable is wrapped in a Ref (which always takes 1 slot).
     * @return The number of JVM local variable slots required (1 or 2).
     */
    fun getSlotsForType(type: ReturnType, isWrapped: Boolean = false): Int {
        if (isWrapped) {
            return 1
        }

        if (type is ClassTypeRef && !type.isNullable) {
            if (type.`package` == "builtin") {
                return when (type.type) {
                    "long", "double" -> 2
                    else -> 1
                }
            }
        }
        return 1
    }
    
    /**
     * Emits unboxing or casting bytecode for Object to target type conversion.
     * 
     * This is primarily used by ForStatementCompiler to convert Iterator.next() results
     * (which return Object) to the loop variable type.
     * 
     * For primitive types, casts to the wrapper type and unboxes (unless nullable).
     * For String, casts to String. For other object types, keeps as Object.
     * 
     * @param targetType The target type to convert to.
     * @param mv The method visitor for emitting bytecode.
     */
    fun emitUnboxOrCast(targetType: ReturnType, mv: MethodVisitor) {
        if (targetType !is ClassTypeRef) {
            return
        }
        if (targetType.`package` == "builtin") {
            when (targetType.type) {
                "int", "long", "float", "double", "boolean" -> {
                    val wrapperClass = when (targetType.type) {
                        "int" -> "java/lang/Integer"
                        "long" -> "java/lang/Long"
                        "float" -> "java/lang/Float"
                        "double" -> "java/lang/Double"
                        "boolean" -> "java/lang/Boolean"
                        else -> throw IllegalStateException("Unexpected type: ${targetType.type}")
                    }
                    mv.visitTypeInsn(Opcodes.CHECKCAST, wrapperClass)
                    if (!targetType.isNullable) {
                        CoercionUtil.emitUnboxing(targetType, mv)
                    }
                }
                "string" -> {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String")
                }
            }
        }
    }

}
