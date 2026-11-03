package com.mdeo.script.compiler.util

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.LambdaType
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.VoidType
import com.mdeo.script.compiler.CompilationContext
import com.mdeo.script.compiler.LambdaInterfaceRegistry
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Unified utility for type coercion, including:
 * - Widening primitive conversions (int→long, int→double, etc.)
 * - Boxing (primitive → wrapper)
 * - Unboxing (wrapper → primitive)
 * 
 * This utility should be used anywhere a type conversion is needed to ensure
 * consistent behavior across the compiler.
 * 
 * ## Architecture
 * 
 * CoercionUtil is the main entry point for all type conversions. It delegates to:
 * - TypeConversionUtil: For widening conversions (int→long, int→double) and opcode generation
 * - Internal boxing/unboxing logic: For primitive ↔ wrapper conversions
 */
object CoercionUtil {
    
    private val PRIMITIVE_TYPES = setOf(
        "int",
        "long",
        "float",
        "double",
        "boolean"
    )
    
    /**
     * Checks if a type name represents a primitive type.
     * 
     * @param typeName The type name to check.
     * @return true if it's a primitive type (int, long, float, double, boolean).
     */
    fun isPrimitiveType(type: ReturnType): Boolean {
        return type is ClassTypeRef && type.`package` == "builtin" && PRIMITIVE_TYPES.contains(type.type)
    }
    
    /**
     * Checks if the source type produces a primitive value on the stack.
     * Non-nullable primitive types and literal expressions produce primitives.
     * Nullable types produce objects on the stack, unless it's a literal expression
     * that always produces a primitive.
     * 
     * @param type The return type to check.
     * @param expression Optional expression to check if it's a literal that always produces a primitive.
     * @return true if the type produces a primitive value on the stack.
     */
    fun producesStackPrimitive(type: ReturnType, expression: TypedExpression? = null): Boolean {
        if (type !is ClassTypeRef) {
            return false
        }
        
        if (type.isNullable) {
            if (expression != null) {
                return isLiteralExpression(expression)
            }
            return false
        }
        
        return isPrimitiveType(type)
    }

    
    /**
     * Checks if an expression is a literal that always produces a primitive.
     * 
     * @param expression The typed expression to check.
     * @return true if the expression is a numeric or boolean literal.
     */
    private fun isLiteralExpression(expression: TypedExpression): Boolean {
        return expression.kind in setOf(
            "intLiteral",
            "longLiteral",
            "floatLiteral",
            "doubleLiteral",
            "booleanLiteral"
        )
    }
    
    /**
     * Checks if an expression is a null literal.
     * 
     * @param expression The typed expression to check.
     * @return true if the expression is a null literal.
     */
    fun isNullLiteral(expression: TypedExpression): Boolean {
        return expression.kind == "nullLiteral"
    }
    
    /**
     * Emits boxing bytecode for a primitive type.
     * Uses valueOf methods: Integer.valueOf(int), Long.valueOf(long), etc.
     *
     * @param typeRef The ClassTypeRef of the primitive type (e.g., builtin.int)
     * @param mv The method visitor
     * @return true if boxing was emitted
     */
    fun emitBoxing(typeRef: ClassTypeRef, mv: MethodVisitor): Boolean = emitBoxingByName(typeRef.type, mv)

    private fun emitBoxingByName(primitiveTypeName: String, mv: MethodVisitor): Boolean {
        return when (primitiveTypeName) {
            "int" -> {
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Integer",
                    "valueOf",
                    "(I)Ljava/lang/Integer;",
                    false
                )
                true
            }
            "long" -> {
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Long",
                    "valueOf",
                    "(J)Ljava/lang/Long;",
                    false
                )
                true
            }
            "float" -> {
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Float",
                    "valueOf",
                    "(F)Ljava/lang/Float;",
                    false
                )
                true
            }
            "double" -> {
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Double",
                    "valueOf",
                    "(D)Ljava/lang/Double;",
                    false
                )
                true
            }
            "boolean" -> {
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Boolean",
                    "valueOf",
                    "(Z)Ljava/lang/Boolean;",
                    false
                )
                true
            }
            else -> false
        }
    }
    
    /**
     * Emits unboxing bytecode for a wrapper type.
     * Uses instance methods: intValue(), longValue(), etc.
     *
     * @param typeRef The ClassTypeRef of the primitive type to unbox to (e.g., builtin.int)
     * @param mv The method visitor
     * @return true if unboxing was emitted
     */
    fun emitUnboxing(typeRef: ClassTypeRef, mv: MethodVisitor): Boolean = emitUnboxingByName(typeRef.type, mv)

    private fun emitUnboxingByName(primitiveTypeName: String, mv: MethodVisitor): Boolean {
        return when (primitiveTypeName) {
            "int" -> {
                mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/Integer",
                    "intValue",
                    "()I",
                    false
                )
                true
            }
            "long" -> {
                mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/Long",
                    "longValue",
                    "()J",
                    false
                )
                true
            }
            "float" -> {
                mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/Float",
                    "floatValue",
                    "()F",
                    false
                )
                true
            }
            "double" -> {
                mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/Double",
                    "doubleValue",
                    "()D",
                    false
                )
                true
            }
            "boolean" -> {
                mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/Boolean",
                    "booleanValue",
                    "()Z",
                    false
                )
                true
            }
            else -> false
        }
    }
    
    /**
     * Emits coercion bytecode to convert from source type to target type.
     * Handles:
     * - Widening primitive conversions (int→long, int→double, etc.)
     * - Boxing when target is nullable and source is primitive
     * - Unboxing when target is primitive and source is nullable wrapper
     * - Lambda type coercion (when context is provided)
     * - No-op when types are compatible
     * 
     * @param sourceType The source type (what's on the stack)
     * @param targetType The target type (what's expected)
     * @param mv The method visitor
     * @param context Optional compilation context (required for lambda coercion)
     * @return true if any conversion was emitted
     */
    fun emitCoercion(
        sourceType: ReturnType,
        targetType: ReturnType,
        mv: MethodVisitor,
        context: CompilationContext? = null
    ): Boolean {
        if (sourceType is LambdaType && targetType is LambdaType) {
            if (context != null) {
                return emitLambdaCoercion(sourceType, targetType, context, mv)
            }
            return false
        }
        
        if (sourceType is ClassTypeRef && targetType is ClassTypeRef) {
            return emitClassTypeCoercion(sourceType, targetType, mv, context)
        }
        
        return false
    }
    
    /**
     * Emits coercion bytecode for class type conversions (primitives and wrappers).
     */
    private fun emitClassTypeCoercion(
        sourceType: ClassTypeRef,
        targetType: ClassTypeRef,
        mv: MethodVisitor,
        context: CompilationContext? = null
    ): Boolean {
        val sourceTypeName = sourceType.type
        val targetTypeName = targetType.type
        
        val sourceIsNullable = sourceType.isNullable
        val targetIsNullable = targetType.isNullable
        
        val sourceIsPrimitive = isPrimitiveType(sourceType)
        val targetIsPrimitive = isPrimitiveType(targetType)
        val sourceIsAny = sourceType.`package` == "builtin" && sourceType.type == "Any"
        
        if (sourceIsPrimitive && !sourceIsNullable && targetIsPrimitive && targetIsNullable) {
            return emitPrimitiveToBoxedConversion(sourceTypeName, targetTypeName, mv)
        }
        
        if (sourceIsPrimitive && !sourceIsNullable && !targetIsPrimitive) {
            return emitBoxingByName(sourceTypeName, mv)
        }
        
        if (sourceIsAny && !targetIsNullable && targetIsPrimitive) {
            return emitAnyToPrimitiveConversion(targetTypeName, mv)
        }
        
        if (sourceIsAny && targetIsNullable && targetIsPrimitive) {
            return emitAnyToBoxedConversion(targetTypeName, mv)
        }
        
        if (sourceIsNullable && sourceIsPrimitive && !targetIsNullable && targetIsPrimitive) {
            return emitBoxedToPrimitiveConversion(sourceTypeName, targetTypeName, mv)
        }
        
        if (!sourceIsNullable && sourceIsPrimitive && !targetIsNullable && targetIsPrimitive) {
            return TypeConversionUtil.emitConversion(sourceTypeName, targetTypeName, mv)
        }
        
        if (sourceType == targetType) {
            return false
        }
        
        if (sourceIsNullable && sourceIsPrimitive && targetIsNullable && targetIsPrimitive && sourceTypeName != targetTypeName) {
            return emitBoxedToBoxedConversion(sourceTypeName, targetTypeName, mv)
        }

        if (!sourceIsPrimitive && !targetIsPrimitive && 
            (sourceType.`package` != targetType.`package` || sourceType.type != targetType.type)) {
            return emitReferenceTypeConversion(sourceType, targetType, mv, context)
        }
        
        return false
    }
    
    /**
     * Emits conversion from Any/Object to a primitive type.
     * CHECKCASTs to the wrapper class, then unboxes.
     * 
     * @param targetTypeName The target primitive type name.
     * @param mv The method visitor.
     * @return true if coercion was emitted.
     */
    private fun emitAnyToPrimitiveConversion(targetTypeName: String, mv: MethodVisitor): Boolean {
        val wrapperClass = getWrapperClassName(targetTypeName) ?: return false
        mv.visitTypeInsn(Opcodes.CHECKCAST, wrapperClass)
        return emitUnboxingByName(targetTypeName, mv)
    }
    
    /**
     * Emits conversion from Any/Object to a nullable primitive (boxed) type.
     * CHECKCASTs to the wrapper class.
     * 
     * @param targetTypeName The target primitive type name.
     * @param mv The method visitor.
     * @return true if coercion was emitted.
     */
    private fun emitAnyToBoxedConversion(targetTypeName: String, mv: MethodVisitor): Boolean {
        val wrapperClass = getWrapperClassName(targetTypeName) ?: return false
        mv.visitTypeInsn(Opcodes.CHECKCAST, wrapperClass)
        return true
    }
    
    /**
     * Gets the JVM internal class name for a primitive type's wrapper.
     * 
     * @param primitiveTypeName The primitive type name (e.g., "builtin.int").
     * @return The wrapper class name (e.g., "java/lang/Integer"), or null if not a primitive.
     */
    private fun getWrapperClassName(primitiveTypeName: String): String? {
        return when (primitiveTypeName) {
            "int" -> "java/lang/Integer"
            "long" -> "java/lang/Long"
            "float" -> "java/lang/Float"
            "double" -> "java/lang/Double"
            "boolean" -> "java/lang/Boolean"
            else -> null
        }
    }
    
    /**
     * Emits conversion from a primitive value to a boxed (nullable) value.
     * First performs any necessary primitive widening conversion, then boxes the result.
     * 
     * @param sourceTypeName The source primitive type name.
     * @param targetTypeName The target primitive type name (for the boxed wrapper).
     * @param mv The method visitor to emit bytecode to.
     * @return true if boxing was emitted.
     */
    private fun emitPrimitiveToBoxedConversion(
        sourceTypeName: String,
        targetTypeName: String,
        mv: MethodVisitor
    ): Boolean {
        if (sourceTypeName != targetTypeName && TypeConversionUtil.isNumericType(sourceTypeName) && TypeConversionUtil.isNumericType(targetTypeName)) {
            TypeConversionUtil.emitConversion(sourceTypeName, targetTypeName, mv)
        }
        return emitBoxingByName(targetTypeName, mv)
    }
    
    /**
     * Emits conversion from a boxed (nullable) value to a primitive value.
     * First unboxes the value, then performs any necessary primitive widening conversion.
     * 
     * @param sourceTypeName The source primitive type name (of the boxed wrapper).
     * @param targetTypeName The target primitive type name.
     * @param mv The method visitor to emit bytecode to.
     * @return true always, as unboxing is always emitted.
     */
    private fun emitBoxedToPrimitiveConversion(
        sourceTypeName: String,
        targetTypeName: String,
        mv: MethodVisitor
    ): Boolean {
        emitUnboxingByName(sourceTypeName, mv)
        if (sourceTypeName != targetTypeName && TypeConversionUtil.isNumericType(sourceTypeName) && TypeConversionUtil.isNumericType(targetTypeName)) {
            TypeConversionUtil.emitConversion(sourceTypeName, targetTypeName, mv)
        }
        return true
    }
    
    /**
     * Emits conversion between two different boxed (nullable) primitive types.
     * Unboxes the source, performs primitive conversion, then reboxes to target type.
     * 
     * @param sourceTypeName The source primitive type name (of the boxed wrapper).
     * @param targetTypeName The target primitive type name (for the boxed wrapper).
     * @param mv The method visitor to emit bytecode to.
     * @return true always, as conversion is always emitted.
     */
    private fun emitBoxedToBoxedConversion(
        sourceTypeName: String,
        targetTypeName: String,
        mv: MethodVisitor
    ): Boolean {
        emitUnboxingByName(sourceTypeName, mv)
        TypeConversionUtil.emitConversion(sourceTypeName, targetTypeName, mv)
        emitBoxingByName(targetTypeName, mv)
        return true
    }
    
    /**
     * Emits conversion between reference types using CHECKCAST.
     * 
     * This handles conversions between non-primitive types, such as:
     * - Any? to string (needs CHECKCAST to String)
     * - Any? to Integer/Long/etc (needs CHECKCAST to wrapper type)
     * - string to Any? (no CHECKCAST needed - upcast is automatic)
     * - Integer to Any? (no CHECKCAST needed - upcast is automatic)
     * - Custom type to Custom type (needs CHECKCAST if different)
     * - Object to wrapper types (needs CHECKCAST)
     * 
     * @param sourceType The source class type reference.
     * @param targetType The target class type reference.
     * @param mv The method visitor to emit bytecode to.
     * @return true if a CHECKCAST was emitted or the conversion is valid.
     */
    private fun emitReferenceTypeConversion(
        sourceType: ClassTypeRef,
        targetType: ClassTypeRef,
        mv: MethodVisitor,
        context: CompilationContext? = null
    ): Boolean {
        if (targetType.`package` == "builtin" && targetType.type == "Any") {
            return false
        }
        
        if (sourceType.`package` == targetType.`package` && sourceType.type == targetType.type 
            && sourceType.isNullable == targetType.isNullable) {
            return false
        }

        if (targetType.`package` != "builtin") {
            val jvmClass = context?.typeRegistry?.getJvmClassName(targetType)
            if (jvmClass != null) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, jvmClass)
                return true
            }
            return false
        }

        val targetDescriptor = ASMUtil.getTypeDescriptor(targetType)
        if (targetDescriptor.startsWith("L") && targetDescriptor.endsWith(";")) {
            val internalName = targetDescriptor.substring(1, targetDescriptor.length - 1)
            mv.visitTypeInsn(Opcodes.CHECKCAST, internalName)
            return true
        }
        
        return false
    }

    
    /**
     * Gets the primitive type descriptor for JVM.
     *
     * Delegates to ASMUtil for consistency.
     *
     * @param typeRef The ClassTypeRef of the primitive type (e.g., builtin.int).
     * @return The JVM type descriptor (e.g., "I" for int), or null if not a primitive type.
     */
    fun getPrimitiveDescriptor(typeRef: ClassTypeRef): String? {
        return ASMUtil.getPrimitiveDescriptor(typeRef)
    }
    
    /**
     * Emits lambda type coercion bytecode.
     * 
     * When the source is a lambda type and the target is a different lambda type,
     * this creates a wrapper lambda that:
     * 1. Takes the target signature's parameters
     * 2. Coerces them to the source lambda's parameter types
     * 3. Calls the source lambda
     * 4. Coerces the return value to the target's return type
     * 
     * @param sourceType The source lambda type (what's on the stack).
     * @param targetType The target lambda type (what's expected).
     * @param context The compilation context.
     * @param mv The method visitor.
     * @return true if coercion was emitted, false if types are compatible.
     */
    fun emitLambdaCoercion(
        sourceType: LambdaType,
        targetType: LambdaType,
        context: CompilationContext,
        mv: MethodVisitor
    ): Boolean {
        if (areLambdaTypesCompatible(sourceType, targetType)) {
            return false
        }
        
        val wrapperMethodName = context.generateLambdaMethodName()
        generateWrapperLambdaMethod(wrapperMethodName, sourceType, targetType, context)
        emitWrapperInvokeDynamic(wrapperMethodName, sourceType, targetType, context, mv)
        
        return true
    }
    
    /**
     * Checks if two lambda types are compatible without needing coercion.
     */
    private fun areLambdaTypesCompatible(source: LambdaType, target: LambdaType): Boolean {
        if (source.parameters.size != target.parameters.size) return false
        if (source.returnType != target.returnType) return false
        
        for (i in source.parameters.indices) {
            if (source.parameters[i].type != target.parameters[i].type) return false
        }
        
        return true
    }
    
    /**
     * Generates a wrapper lambda method that adapts the source lambda to the target signature.
     */
    private fun generateWrapperLambdaMethod(
        methodName: String,
        sourceType: LambdaType,
        targetType: LambdaType,
        context: CompilationContext
    ) {
        val cw = context.classWriter
            ?: throw IllegalStateException("ClassWriter required for lambda coercion")
        
        val sourceInterfaceName = getInterfaceForLambdaType(sourceType, context)
        val wrapperDescriptor = buildWrapperMethodDescriptor(sourceInterfaceName, targetType, context)
        
        val wrapperMv = cw.visitMethod(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
            methodName,
            wrapperDescriptor,
            null,
            null
        )
        
        wrapperMv.visitCode()
        emitWrapperMethodBody(wrapperMv, sourceType, targetType, context)
        wrapperMv.visitMaxs(0, 0)
        wrapperMv.visitEnd()
    }
    
    /**
     * Emits the body of the wrapper method that calls the source lambda with coerced params.
     */
    private fun emitWrapperMethodBody(
        mv: MethodVisitor,
        sourceType: LambdaType,
        targetType: LambdaType,
        context: CompilationContext
    ) {
        val sourceInterfaceName = getInterfaceForLambdaType(sourceType, context)
        
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        
        var paramSlot = 1
        for (i in sourceType.parameters.indices) {
            val sourceParamType = sourceType.parameters[i].type
            val targetParamType = targetType.parameters[i].type
            
            mv.visitVarInsn(ASMUtil.getLoadOpcode(targetParamType), paramSlot)
            emitCoercion(targetParamType, sourceParamType, mv)
            
            paramSlot += ASMUtil.getSlotsForType(targetParamType)
        }
        
        val sourceMethodDescriptor = buildLambdaCallDescriptor(sourceType, context)
        mv.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            sourceInterfaceName,
            "call",
            sourceMethodDescriptor,
            true
        )
        
        emitCoercion(sourceType.returnType, targetType.returnType, mv)
        
        emitReturnInstruction(targetType.returnType, mv)
    }
    
    /**
     * Builds the method descriptor for the wrapper synthetic method.
     * Takes the source lambda interface as first param, then target param types.
     */
    private fun buildWrapperMethodDescriptor(
        sourceInterfaceName: String,
        targetType: LambdaType,
        context: CompilationContext
    ): String {
        val params = StringBuilder()
        params.append("L").append(sourceInterfaceName).append(";")
        
        for (i in 0 until targetType.parameters.size.coerceAtMost(
            targetType.parameters.size
        )) {
            val paramType = targetType.parameters[i].type
            params.append(context.getTypeDescriptor(paramType))
        }
        
        val returnDesc = context.getTypeDescriptor(targetType.returnType)
        return "(${params})$returnDesc"
    }
    
    /**
     * Builds the method descriptor for calling the source lambda's call method.
     */
    private fun buildLambdaCallDescriptor(lambdaType: LambdaType, context: CompilationContext): String {
        val params = lambdaType.parameters.joinToString("") { param ->
            context.getTypeDescriptor(param.type)
        }
        val returnDesc = context.getTypeDescriptor(lambdaType.returnType)
        return "(${params})$returnDesc"
    }
    
    /**
     * Emits the invokedynamic instruction to create the wrapper lambda.
     */
    private fun emitWrapperInvokeDynamic(
        methodName: String,
        sourceType: LambdaType,
        targetType: LambdaType,
        context: CompilationContext,
        mv: MethodVisitor
    ) {
        val sourceInterfaceName = getInterfaceForLambdaType(sourceType, context)
        val targetInterfaceName = getInterfaceForLambdaType(targetType, context)
        
        val capturedDesc = "L${sourceInterfaceName};"
        val invokeDynamicDesc = "(${capturedDesc})L${targetInterfaceName};"
        
        val samMethodType = buildErasedOrActualMethodType(targetType, context)
        val wrapperDescriptor = buildWrapperMethodDescriptor(sourceInterfaceName, targetType, context)
        
        val implMethodHandle = Handle(
            Opcodes.H_INVOKESTATIC,
            context.currentClassName,
            methodName,
            wrapperDescriptor,
            false
        )
        
        val instantiatedMethodType = buildInstantiatedMethodType(targetType, context)
        
        mv.visitInvokeDynamicInsn(
            "call",
            invokeDynamicDesc,
            Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                false
            ),
            samMethodType,
            implMethodHandle,
            instantiatedMethodType
        )
    }
    
    /**
     * Gets the functional interface for a lambda type, generating its bytecode the first time the
     * type is seen.
     *
     * @param lambdaType The lambda type.
     * @param context The compilation context.
     * @return The internal name of the functional interface.
     */
    fun getInterfaceForLambdaType(lambdaType: LambdaType, context: CompilationContext): String {
        val registry = context.getLambdaInterfaceRegistry()
        val lookupResult = registry.getInterfaceForLambdaType(lambdaType)
        
        if (lookupResult.isNewlyGenerated) {
            val bytecode = generateFunctionalInterfaceBytecode(lookupResult.interfaceName, lambdaType, context)
            context.registerInterface(lookupResult.interfaceName, bytecode)
        }
        
        return lookupResult.interfaceName
    }
    
    /**
     * Generates bytecode for a functional interface.
     */
    private fun generateFunctionalInterfaceBytecode(
        interfaceName: String,
        lambdaType: LambdaType,
        context: CompilationContext
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        
        cw.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
            interfaceName,
            null,
            "java/lang/Object",
            null
        )
        
        val methodDescriptor = buildLambdaCallDescriptor(lambdaType, context)
        
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
            "call",
            methodDescriptor,
            null,
            null
        )?.visitEnd()
        
        cw.visitEnd()
        
        return cw.toByteArray()
    }
    
    /**
     * Builds the SAM method type for the target interface's call method.
     *
     * Uses the exact types from the lambda type, so that concrete return types
     * (e.g. boolean for Predicate1) are preserved rather than erased to Object.
     */
    private fun buildErasedOrActualMethodType(lambdaType: LambdaType, context: CompilationContext): Type {
        return Type.getMethodType(buildLambdaCallDescriptor(lambdaType, context))
    }
    
    /**
     * Builds the instantiated method type for the target lambda.
     *
     * Uses the exact types from the lambda type, matching the interface method descriptor.
     */
    private fun buildInstantiatedMethodType(lambdaType: LambdaType, context: CompilationContext): Type {
        return Type.getMethodType(buildLambdaCallDescriptor(lambdaType, context))
    }
    
    /**
     * Emits the appropriate return instruction for a type.
     */
    private fun emitReturnInstruction(returnType: ReturnType, mv: MethodVisitor) {
        when {
            returnType is VoidType -> mv.visitInsn(Opcodes.RETURN)
            returnType is ClassTypeRef && !returnType.isNullable -> {
                if (returnType.`package` == "builtin") {
                    when (returnType.type) {
                        "int", "boolean" -> mv.visitInsn(Opcodes.IRETURN)
                        "long" -> mv.visitInsn(Opcodes.LRETURN)
                        "float" -> mv.visitInsn(Opcodes.FRETURN)
                        "double" -> mv.visitInsn(Opcodes.DRETURN)
                        else -> mv.visitInsn(Opcodes.ARETURN)
                    }
                } else {
                    mv.visitInsn(Opcodes.ARETURN)
                }
            }
            else -> mv.visitInsn(Opcodes.ARETURN)
        }
    }
}
