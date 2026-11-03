package com.mdeo.script.compiler.statements

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.statements.TypedStatement
import com.mdeo.expression.ast.statements.TypedVariableDeclarationStatement
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.script.compiler.util.ASMUtil
import com.mdeo.script.compiler.CompilationContext
import com.mdeo.script.compiler.RefTypeUtil
import com.mdeo.script.compiler.StatementCompiler
import com.mdeo.script.compiler.VariableInfo
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Compiles variable declaration statements to bytecode.
 * 
 * For variables NOT written by lambdas:
 * - If there's an initial value, compile it and store to the slot
 * - If no initial value, do nothing (JVM initializes to default)
 * 
 * For variables written by lambdas:
 * - Create a new Ref instance with the default or initial value
 * - Store the Ref to the slot
 */
class VariableDeclarationCompiler : StatementCompiler {
    
    /**
     * Checks if this compiler can compile the given statement.
     *
     * @param statement the statement to check
     * @return true if the statement is a TypedVariableDeclarationStatement, false otherwise
     */
    override fun canCompile(statement: TypedStatement): Boolean {
        return statement is TypedVariableDeclarationStatement
    }
    
    /**
     * Compiles a variable declaration statement to bytecode.
     *
     * If compiling within a lambda body, uses the LambdaBodyScope for slot allocation.
     * Otherwise, looks up the variable in the current scope and either wraps it in a Ref
     * (if written by lambdas) or stores it directly.
     *
     * @param statement the variable declaration statement to compile
     * @param context the compilation context containing scope and type information
     * @param mv the method visitor for emitting bytecode
     */
    override fun compile(statement: TypedStatement, context: CompilationContext, mv: MethodVisitor) {
        val declaration = statement as TypedVariableDeclarationStatement
        
        val currentScope = context.currentScope
            ?: throw IllegalStateException("No current scope available for variable declaration: ${declaration.name}")
        
        val variable = currentScope.lookupVariable(declaration.name, currentScope.level)
            ?: throw IllegalStateException("Variable not found in scope: ${declaration.name}")
        
        val type = variable.type
        
        if (variable.isWrittenByLambda) {
            compileRefVariable(declaration, variable, type, context, mv)
        } else {
            compileDirectVariable(declaration, variable, type, context, mv)
        }
    }
    
    /**
     * Compiles a variable that is NOT wrapped in a Ref.
     *
     * If the variable has an initial value, compiles it and applies necessary type coercion.
     * For lambda expressions, registers the variable-to-lambda class mapping for later reference.
     * If no initial value is present, emits a default value to ensure proper initialization
     * (JVM local variables are not automatically initialized like fields).
     * Uses CoercionUtil for unified type conversion (handles boxing, unboxing, widening),
     * with fallback to simple numeric conversion if coercion didn't apply.
     *
     * @param declaration the variable declaration statement to compile
     * @param variable the variable info containing slot and type information
     * @param type the resolved return type of the variable
     * @param context the compilation context containing scope and expression compilation utilities
     * @param mv the method visitor for emitting bytecode
     */
    private fun compileDirectVariable(
        declaration: TypedVariableDeclarationStatement,
        variable: VariableInfo,
        type: ReturnType,
        context: CompilationContext,
        mv: MethodVisitor
    ) {
        val init = declaration.initialValue
        if (init != null) {
            context.compileExpression(init, mv, type)
        } else {
            ASMUtil.emitZeroValue(type, mv)
        }
        
        val storeOpcode = ASMUtil.getStoreOpcode(type)
        mv.visitVarInsn(storeOpcode, variable.slotIndex)
    }
    
    /**
     * Compiles a variable that IS wrapped in a Ref.
     *
     * Creates a new Ref instance, initializes it with either the provided initial value
     * or uses the default Ref constructor, and stores it to the variable's slot.
     *
     * @param declaration the variable declaration statement to compile
     * @param variable the variable info containing slot and type information
     * @param type the resolved return type of the variable
     * @param context the compilation context containing expression compilation utilities
     * @param mv the method visitor for emitting bytecode
     */
    private fun compileRefVariable(
        declaration: TypedVariableDeclarationStatement,
        variable: VariableInfo,
        type: ReturnType,
        context: CompilationContext,
        mv: MethodVisitor
    ) {
        val refClassName = RefTypeUtil.getRefClassName(type)
        
        mv.visitTypeInsn(Opcodes.NEW, refClassName)
        mv.visitInsn(Opcodes.DUP)
        
        val init = declaration.initialValue
        if (init != null) {
            context.compileExpression(init, mv, type)
            
            val constructorDescriptor = getRefConstructorDescriptor(type)
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, refClassName, "<init>", constructorDescriptor, false)
        } else {
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, refClassName, "<init>", "()V", false)
        }
        
        mv.visitVarInsn(Opcodes.ASTORE, variable.slotIndex)
    }
    
    /**
     * Gets the constructor descriptor for a Ref type with initial value.
     *
     * Returns the JVM method descriptor for the Ref constructor that accepts
     * an initial value of the appropriate type.
     *
     * @param type the return type to get the Ref constructor descriptor for
     * @return the JVM method descriptor string for the Ref constructor
     */
    private fun getRefConstructorDescriptor(type: ReturnType): String {
        if (type is ClassTypeRef && type.`package` == "builtin") {
            return when (type.type) {
                "int" -> {
                    "(I)V"
                }
                "long" -> {
                    "(J)V"
                }
                "float" -> {
                    "(F)V"
                }
                "double" -> {
                    "(D)V"
                }
                else -> {
                    "(Ljava/lang/Object;)V"
                }
            }
        }
        return "(Ljava/lang/Object;)V"
    }
    

}
