import type {
    UnaryExpressionType,
    BinaryExpressionType,
    TernaryExpressionType,
    AssertNonNullExpressionType,
    TypeCastExpressionType,
    TypeCheckExpressionType,
    CallExpressionType,
    MemberAccessExpressionType,
    MemberCallExpressionType,
    IdentifierExpressionType,
    StringLiteralExpressionType,
    IntLiteralExpressionType,
    LongLiteralExpressionType,
    FloatLiteralExpressionType,
    DoubleLiteralExpressionType,
    BooleanLiteralExpressionType,
    ListExpressionType,
    ExpressionTypes
} from "../grammar/expressionTypes.js";
import type { ReturnType, ValueType, ClassTypeRef } from "../typir-extensions/config/type.js";
import { isCustomVoidType } from "../typir-extensions/kinds/custom-void/custom-void-type.js";
import { isCustomClassType } from "../typir-extensions/kinds/custom-class/custom-class-type.js";
import { isCustomLambdaType } from "../typir-extensions/kinds/custom-lambda/custom-lambda-type.js";
import type { CustomLambdaType } from "../typir-extensions/kinds/custom-lambda/custom-lambda-type.js";
import { isCustomFunctionType } from "../typir-extensions/kinds/custom-function/custom-function-type.js";
import type { CustomFunctionType } from "../typir-extensions/kinds/custom-function/custom-function-type.js";
import { isCustomNullType } from "../typir-extensions/kinds/custom-null/custom-null-type.js";
import { getResolvedCallInfo } from "../typir-extensions/rules/getResolvedCallInfo.js";
import type { ResolvedCallInfo } from "../typir-extensions/rules/getResolvedCallInfo.js";
import { DefaultTypeNames } from "../type-system/typeSystemConfig.js";
import type { AstNode } from "langium";
import type { AstReflection } from "@mdeo/language-common";
import type { Type } from "typir";
import type { ExtendedTypirServices } from "../typir-extensions/service/extendedTypirServices.js";
import {
    type TypedExpression,
    type TypedCallArgument,
    type TypedUnaryExpression,
    type TypedBinaryExpression,
    type TypedTernaryExpression,
    type TypedMemberAccessExpression,
    type TypedIdentifierExpression,
    type TypedStringLiteralExpression,
    type TypedIntLiteralExpression,
    type TypedLongLiteralExpression,
    type TypedFloatLiteralExpression,
    type TypedDoubleLiteralExpression,
    type TypedBooleanLiteralExpression,
    type TypedNullLiteralExpression,
    type TypedListExpression,
    type TypedAssertNonNullExpression,
    type TypedTypeCastExpression,
    type TypedTypeCheckExpression,
    type TypedExpressionCallExpression,
    type TypedFunctionCallExpression,
    type TypedMemberCallExpression
} from "./typedAst.js";
import { inferMethodAccess, inferPropertyAccess } from "../typir-extensions/rules/inferMemberAccess.js";

/**
 * Base converter for converting expression ASTs to TypedAST.
 * Handles type inference and AST transformation for expressions.
 * Can be extended for language-specific conversion (e.g., script-specific features, transformation-specific features).
 */
export abstract class TypedAstConverter {
    /**
     * Lookup map to deduplicate types in the types array.
     */
    protected readonly typeLookup = new Map<Type, number>();

    /**
     * Array of all types used in the program.
     */
    protected readonly types: ReturnType[] = [];

    /**
     * The Any? type used for generic type parameter replacement.
     */
    protected readonly anyNullableType: ClassTypeRef = {
        package: "builtin",
        type: DefaultTypeNames.Any,
        isNullable: true
    };

    /**
     * Index for the void type in the types array.
     */
    protected voidTypeIndex: number;

    /**
     * Index for the string type in the types array.
     */
    protected stringTypeIndex: number;

    /**
     * Index for the double type in the types array.
     */
    protected doubleTypeIndex: number;

    /**
     * Index for the boolean type in the types array.
     */
    protected booleanTypeIndex: number;

    /**
     * Index for the null type in the types array.
     */
    protected nullTypeIndex: number;

    /**
     * Expression type identifiers for checking instance types.
     */
    protected abstract expressionTypes: ExpressionTypes;

    /**
     * Creates a new TypedAstConverter.
     *
     * @param typir The Typir services for type inference
     * @param reflection The AST reflection for type checking
     */
    constructor(
        protected readonly typir: ExtendedTypirServices<any>,
        protected readonly reflection: AstReflection
    ) {
        this.voidTypeIndex = this.getTypeIndexForType(typir.factory.CustomVoid.getOrCreate());
        this.stringTypeIndex = this.getTypeIndexForType(
            typir.TypeDefinitions.resolveCustomClassOrLambdaType({
                package: "builtin",
                type: DefaultTypeNames.String,
                isNullable: false
            })
        );
        this.doubleTypeIndex = this.getTypeIndexForType(
            typir.TypeDefinitions.resolveCustomClassOrLambdaType({
                package: "builtin",
                type: DefaultTypeNames.Double,
                isNullable: false
            })
        );
        this.booleanTypeIndex = this.getTypeIndexForType(
            typir.TypeDefinitions.resolveCustomClassOrLambdaType({
                package: "builtin",
                type: DefaultTypeNames.Boolean,
                isNullable: false
            })
        );
        this.nullTypeIndex = this.getTypeIndexForType(typir.factory.CustomNull.getOrCreate());
    }

    /**
     * Converts an expression AST node to a TypedExpression.
     * This is an abstract method that must be implemented by subclasses to handle
     * language-specific expression types (like lambdas, function calls, etc.).
     *
     * @param expr The expression AST node
     * @returns The TypedExpression representation
     */
    protected convertExpression(expr: AstNode): TypedExpression {
        if (this.reflection.isInstance(expr, this.expressionTypes.unaryExpressionType)) {
            return this.convertUnaryExpression(expr as UnaryExpressionType);
        } else if (this.reflection.isInstance(expr, this.expressionTypes.binaryExpressionType)) {
            return this.convertBinaryExpression(expr as BinaryExpressionType);
        } else if (this.reflection.isInstance(expr, this.expressionTypes.ternaryExpressionType)) {
            return this.convertTernaryExpression(expr as TernaryExpressionType);
        } else if (this.reflection.isInstance(expr, this.expressionTypes.assertNonNullExpressionType)) {
            return this.convertAssertNonNullExpression(expr as AssertNonNullExpressionType);
        } else if (this.reflection.isInstance(expr, this.expressionTypes.typeCastExpressionType)) {
            return this.convertTypeCastExpression(expr as TypeCastExpressionType);
        } else if (this.reflection.isInstance(expr, this.expressionTypes.typeCheckExpressionType)) {
            return this.convertTypeCheckExpression(expr as TypeCheckExpressionType);
        } else if (this.reflection.isInstance(expr, this.expressionTypes.memberCallExpressionType)) {
            return this.convertMemberCallExpression(expr as MemberCallExpressionType);
        } else if (this.reflection.isInstance(expr, this.expressionTypes.callExpressionType)) {
            return this.convertCallExpression(expr as CallExpressionType);
        } else if (this.reflection.isInstance(expr, this.expressionTypes.memberAccessExpressionType)) {
            return this.convertMemberAccessExpression(expr as MemberAccessExpressionType);
        } else if (this.reflection.isInstance(expr, this.expressionTypes.identifierExpressionType)) {
            return this.convertIdentifierExpression(expr as IdentifierExpressionType);
        } else if (this.reflection.isInstance(expr, this.expressionTypes.stringLiteralExpressionType)) {
            return this.convertStringLiteral(expr as StringLiteralExpressionType);
        } else if (this.reflection.isInstance(expr, this.expressionTypes.intLiteralExpressionType)) {
            return this.convertIntLiteral(expr as IntLiteralExpressionType);
        } else if (this.reflection.isInstance(expr, this.expressionTypes.longLiteralExpressionType)) {
            return this.convertLongLiteral(expr as LongLiteralExpressionType);
        } else if (this.reflection.isInstance(expr, this.expressionTypes.floatLiteralExpressionType)) {
            return this.convertFloatLiteral(expr as FloatLiteralExpressionType);
        } else if (this.reflection.isInstance(expr, this.expressionTypes.doubleLiteralExpressionType)) {
            return this.convertDoubleLiteral(expr as DoubleLiteralExpressionType);
        } else if (this.reflection.isInstance(expr, this.expressionTypes.booleanLiteralExpressionType)) {
            return this.convertBooleanLiteral(expr as BooleanLiteralExpressionType);
        } else if (this.reflection.isInstance(expr, this.expressionTypes.nullLiteralExpressionType)) {
            return this.convertNullLiteral();
        } else if (this.reflection.isInstance(expr, this.expressionTypes.listExpressionType)) {
            return this.convertListExpression(expr as ListExpressionType);
        }
        return this.convertAdditionalExpression(expr);
    }

    /**
     * Hook for subclasses to handle additional expression types not covered by the base converter.
     * Should throw an error if the expression type is not supported.
     *
     * @param expr The expression AST node
     * @returns The TypedExpression representation
     */
    protected convertAdditionalExpression(expr: AstNode): TypedExpression {
        throw new Error(`Unsupported expression type: ${expr}`);
    }

    /**
     * Converts a unary expression.
     *
     * @param expr The unary expression AST node
     * @returns The TypedUnaryExpression representation
     */
    protected convertUnaryExpression(expr: UnaryExpressionType): TypedUnaryExpression {
        return {
            kind: "unary",
            evalType: this.getTypeIndex(expr),
            operator: expr.operator as "-" | "!",
            expression: this.convertExpression(expr.expression)
        };
    }

    /**
     * Converts a binary expression.
     *
     * @param expr The binary expression AST node
     * @returns The TypedBinaryExpression representation
     */
    protected convertBinaryExpression(expr: BinaryExpressionType): TypedBinaryExpression {
        return {
            kind: "binary",
            evalType: this.getTypeIndex(expr),
            operator: expr.operator as TypedBinaryExpression["operator"],
            left: this.convertExpression(expr.left),
            right: this.convertExpression(expr.right)
        };
    }

    /**
     * Converts a ternary expression.
     *
     * @param expr The ternary expression AST node
     * @returns The TypedTernaryExpression representation
     */
    protected convertTernaryExpression(expr: TernaryExpressionType): TypedTernaryExpression {
        return {
            kind: "ternary",
            evalType: this.getTypeIndex(expr),
            condition: this.convertExpression(expr.condition),
            trueExpression: this.convertExpression(expr.trueExpression),
            falseExpression: this.convertExpression(expr.falseExpression)
        };
    }

    /**
     * Converts an assert non-null expression (!! postfix operator).
     *
     * @param expr The assert non-null expression AST node
     * @returns The TypedAssertNonNullExpression representation
     */
    protected convertAssertNonNullExpression(expr: AssertNonNullExpressionType): TypedAssertNonNullExpression {
        return {
            kind: "assertNonNull",
            evalType: this.getTypeIndex(expr),
            expression: this.convertExpression(expr.expression)
        };
    }

    /**
     * Converts a type cast expression (as / as? operators).
     *
     * @param expr The type cast expression AST node
     * @returns The TypedTypeCastExpression representation
     */
    protected convertTypeCastExpression(expr: TypeCastExpressionType): TypedTypeCastExpression {
        return {
            kind: "typeCast",
            evalType: this.getTypeIndex(expr),
            expression: this.convertExpression(expr.expression),
            targetType: this.getTypeIndex(expr.targetType),
            isSafe: expr.isSafe
        };
    }

    /**
     * Converts a type check expression (is / !is operators).
     *
     * @param expr The type check expression AST node
     * @returns The TypedTypeCheckExpression representation
     */
    protected convertTypeCheckExpression(expr: TypeCheckExpressionType): TypedTypeCheckExpression {
        return {
            kind: "typeCheck",
            evalType: this.getTypeIndex(expr),
            expression: this.convertExpression(expr.expression),
            checkType: this.getTypeIndex(expr.checkType),
            isNegated: expr.isNegated
        };
    }

    /**
     * Converts a member call expression (e.g. `obj.method(args)`) - the unified
     * AST node produced by the memberCallPostfixFragment grammar rule.
     *
     * @param expr The member call expression AST node
     * @returns The TypedMemberCallExpression/TypedExpressionCallExpression representation
     */
    protected convertMemberCallExpression(
        expr: MemberCallExpressionType
    ): TypedMemberCallExpression | TypedExpressionCallExpression {
        const convertedArgs = expr.arguments.map((arg) => this.convertExpression(arg));
        const evalType = this.getTypeIndex(expr);
        const methodType = inferMethodAccess(expr, expr.expression, expr.member, this.typir);
        if (isCustomFunctionType(methodType)) {
            const callInfo = getResolvedCallInfo(
                expr,
                methodType,
                expr.genericArgs?.typeArguments ?? [],
                expr.arguments,
                this.typir
            );
            if (callInfo == undefined) {
                throw new Error(`Could not determine overload for member call '${expr.member}'`);
            }
            return {
                kind: "memberCall",
                evalType,
                expression: this.convertExpression(expr.expression),
                member: expr.member,
                isNullChaining: expr.isNullChaining ?? false,
                arguments: this.buildCallArguments(convertedArgs, callInfo),
                overload: callInfo.overloadName
            };
        }
        const propertyType = inferPropertyAccess(expr, expr.expression, expr.member, this.typir);
        if (Array.isArray(propertyType) || !isCustomLambdaType(propertyType)) {
            throw new Error(`Could not determine callable type for member call '${expr.member}'`);
        }
        const memberAccess: TypedMemberAccessExpression = {
            kind: "memberAccess",
            evalType: this.getTypeIndexForType(propertyType),
            expression: this.convertExpression(expr.expression),
            member: expr.member,
            isNullChaining: expr.isNullChaining ?? false
        };
        return {
            kind: "call",
            evalType,
            expression: memberAccess,
            arguments: this.buildLambdaCallArguments(convertedArgs, propertyType)
        };
    }

    /**
     * Converts a call expression.
     *
     * @param expr The call expression AST node
     * @returns The TypedCallExpression representation
     */
    protected convertCallExpression(
        expr: CallExpressionType
    ): TypedExpressionCallExpression | TypedFunctionCallExpression | TypedMemberCallExpression {
        const expression = expr.expression;
        const expressionType = this.typir.Inference.inferType(expression);
        if (Array.isArray(expressionType)) {
            throw new Error("Cannot infer type for call expression");
        }
        const convertedArgs = expr.arguments.map((arg) => this.convertExpression(arg));
        const evalType = this.getTypeIndex(expr);
        if (isCustomFunctionType(expressionType)) {
            return this.convertFunctionCallExpression(expr, expressionType, convertedArgs, evalType);
        } else if (isCustomLambdaType(expressionType)) {
            return {
                kind: "call",
                evalType,
                expression: this.convertExpression(expr.expression),
                arguments: this.buildLambdaCallArguments(convertedArgs, expressionType)
            };
        } else {
            return {
                kind: "call",
                evalType,
                expression: this.convertExpression(expr.expression),
                arguments: convertedArgs.map((arg) => ({
                    value: arg,
                    parameterType: arg.evalType
                }))
            };
        }
    }

    /**
     * Converts a function call expression.
     * Only handles calls to named functions and member functions.
     *
     * @param expr The call expression AST node
     * @param functionType The resolved function type
     * @param convertedArgs The converted argument expressions
     * @param evalType The evaluated type index of the call expression
     * @returns The TypedFunctionCallExpression or TypedMemberCallExpression representation
     */
    protected convertFunctionCallExpression(
        expr: CallExpressionType,
        functionType: CustomFunctionType,
        convertedArgs: TypedExpression[],
        evalType: number
    ): TypedFunctionCallExpression | TypedMemberCallExpression {
        const expression = expr.expression;
        const callInfo = getResolvedCallInfo(
            expr,
            functionType,
            expr.genericArgs?.typeArguments ?? [],
            expr.arguments,
            this.typir
        );
        if (callInfo == undefined) {
            throw new Error("Could not determine overload for function call");
        }
        if (this.reflection.isInstance(expression, this.expressionTypes.identifierExpressionType)) {
            return {
                kind: "functionCall",
                evalType,
                name: (expression as IdentifierExpressionType).name,
                arguments: this.buildCallArguments(convertedArgs, callInfo),
                overload: callInfo.overloadName
            };
        } else {
            throw new Error("Unsupported function call expression type");
        }
    }

    /**
     * Converts a member access expression.
     *
     * @param expr The member access expression AST node
     * @returns The TypedMemberAccessExpression representation
     */
    protected convertMemberAccessExpression(expr: MemberAccessExpressionType): TypedMemberAccessExpression {
        return {
            kind: "memberAccess",
            evalType: this.getTypeIndex(expr),
            expression: this.convertExpression(expr.expression),
            member: expr.member,
            isNullChaining: expr.isNullChaining ?? false
        };
    }

    /**
     * Converts an identifier expression.
     *
     * @param expr The identifier expression AST node
     * @returns The TypedIdentifierExpression representation
     */
    protected convertIdentifierExpression(expr: IdentifierExpressionType): TypedIdentifierExpression {
        const scope = this.typir.ScopeProvider.getScope(expr);
        const entry = scope.getEntry(expr.name);

        if (entry == undefined) {
            throw new Error(`Identifier '${expr.name}' not found in scope`);
        }

        return {
            kind: "identifier",
            evalType: this.getTypeIndex(expr),
            name: expr.name,
            scope: entry?.definingScope.level
        };
    }

    /**
     * Converts a string literal expression.
     *
     * @param expr The string literal AST node
     * @returns The TypedStringLiteralExpression representation
     */
    protected convertStringLiteral(expr: StringLiteralExpressionType): TypedStringLiteralExpression {
        return {
            kind: "stringLiteral",
            evalType: this.stringTypeIndex,
            value: expr.value
        };
    }

    /**
     * Converts an int literal expression.
     *
     * @param expr The int literal AST node
     * @returns The TypedIntLiteralExpression representation
     */
    protected convertIntLiteral(expr: IntLiteralExpressionType): TypedIntLiteralExpression {
        return {
            kind: "intLiteral",
            evalType: this.getTypeIndex(expr),
            value: expr.value
        };
    }

    /**
     * Converts a long literal expression.
     *
     * @param expr The long literal AST node
     * @returns The TypedLongLiteralExpression representation
     */
    protected convertLongLiteral(expr: LongLiteralExpressionType): TypedLongLiteralExpression {
        return {
            kind: "longLiteral",
            evalType: this.getTypeIndex(expr),
            value: expr.value.replace(/[Ll]$/, "")
        };
    }

    /**
     * Converts a float literal expression.
     *
     * @param expr The float literal AST node
     * @returns The TypedFloatLiteralExpression representation
     */
    protected convertFloatLiteral(expr: FloatLiteralExpressionType): TypedFloatLiteralExpression {
        return {
            kind: "floatLiteral",
            evalType: this.getTypeIndex(expr),
            value: expr.value
        };
    }

    /**
     * Converts a double literal expression.
     *
     * @param expr The double literal AST node
     * @returns The TypedDoubleLiteralExpression representation
     */
    protected convertDoubleLiteral(expr: DoubleLiteralExpressionType): TypedDoubleLiteralExpression {
        return {
            kind: "doubleLiteral",
            evalType: this.doubleTypeIndex,
            value: expr.value.replace(/[Dd]$/, "")
        };
    }

    /**
     * Converts a boolean literal expression.
     *
     * @param expr The boolean literal AST node
     * @returns The TypedBooleanLiteralExpression representation
     */
    protected convertBooleanLiteral(expr: BooleanLiteralExpressionType): TypedBooleanLiteralExpression {
        return {
            kind: "booleanLiteral",
            evalType: this.getTypeIndex(expr),
            value: expr.value
        };
    }

    /**
     * Converts a null literal expression.
     *
     * @returns The TypedNullLiteralExpression representation
     */
    protected convertNullLiteral(): TypedNullLiteralExpression {
        return {
            kind: "nullLiteral",
            evalType: this.nullTypeIndex
        };
    }

    /**
     * Converts a list expression.
     *
     * @param expr The list expression AST node
     * @returns The TypedListExpression representation
     */
    protected convertListExpression(expr: ListExpressionType): TypedListExpression {
        return {
            kind: "listLiteral",
            evalType: this.getTypeIndex(expr),
            elements: expr.elements.map((element) => this.convertExpression(element))
        };
    }

    /**
     * Gets the index of a type in the types array for the given AST node.
     * Infers the type, erases generic parameters, and adds to the types array if not already present.
     *
     * @param node The AST node to get the type for
     * @returns The index into the types array
     */
    protected getTypeIndex(node: AstNode): number {
        const inferredType = this.typir.Inference.inferType(node);
        if (Array.isArray(inferredType)) {
            throw new Error("Cannot infer type");
        }
        return this.getTypeIndexForType(inferredType);
    }

    /**
     * Gets the index of a type in the types array.
     * Adds the type to the types array if not already present.
     * This method is public to allow external converters to resolve types.
     *
     * @param type The type to get the index for
     * @returns The index into the types array
     */
    public getTypeIndexForType(type: Type): number {
        if (this.typeLookup.has(type)) {
            return this.typeLookup.get(type)!;
        }

        let returnType: ReturnType;
        if (isCustomVoidType(type)) {
            returnType = { kind: "void" };
        } else if (isCustomClassType(type)) {
            returnType = type.definition;
        } else if (isCustomLambdaType(type)) {
            returnType = type.definition;
        } else if (isCustomNullType(type)) {
            returnType = this.anyNullableType;
        } else {
            throw new Error("Unsupported type for indexing");
        }

        const erasedType = this.eraseGenericTypeParameters(returnType);
        const newIndex = this.types.length;
        this.types.push(erasedType);
        this.typeLookup.set(type, newIndex);
        return newIndex;
    }

    /**
     * Erases generic type parameters from a type, replacing them with Any?.
     *
     * @param type The type to erase generics from
     * @returns The type with generics erased
     */
    protected eraseGenericTypeParameters(type: ReturnType): ReturnType {
        if ("kind" in type) {
            return type;
        } else if ("generic" in type) {
            return this.anyNullableType;
        } else if ("type" in type) {
            return {
                package: type.package,
                type: type.type,
                isNullable: type.isNullable,
                typeArgs:
                    type.typeArgs != undefined
                        ? Object.fromEntries(
                              Object.entries(type.typeArgs).map(([argName, argType]) => [
                                  argName,
                                  this.eraseGenericTypeParameters(argType) as ValueType
                              ])
                          )
                        : undefined
            };
        } else {
            return {
                isNullable: type.isNullable,
                parameters: type.parameters.map((parameter) => ({
                    name: parameter.name,
                    type: this.eraseGenericTypeParameters(parameter.type) as ValueType
                })),
                returnType: this.eraseGenericTypeParameters(type.returnType) as ValueType
            };
        }
    }

    /**
     * Builds {@link TypedCallArgument} wrappers for function/method call arguments
     * using the resolved parameter types from overload resolution.
     *
     * For varargs signatures, arguments beyond the regular parameters use the
     * last declared parameter type (the varargs element type).
     *
     * @param convertedArgs The converted argument expressions
     * @param callInfo The resolved call info with parameter types and varargs info
     * @returns Array of TypedCallArgument with resolved parameter types
     */
    protected buildCallArguments(convertedArgs: TypedExpression[], callInfo: ResolvedCallInfo): TypedCallArgument[] {
        return convertedArgs.map((arg, index) => {
            const paramIndex = callInfo.isVarArgs ? Math.min(index, callInfo.resolvedParameterTypes.length - 1) : index;
            const paramType =
                paramIndex >= 0 && paramIndex < callInfo.resolvedParameterTypes.length
                    ? this.getTypeIndexForType(callInfo.resolvedParameterTypes[paramIndex]!)
                    : arg.evalType;
            return { value: arg, parameterType: paramType };
        });
    }

    /**
     * Builds {@link TypedCallArgument} wrappers for lambda call arguments
     * using the lambda's declared parameter types.
     *
     * @param convertedArgs The converted argument expressions
     * @param lambdaType The lambda type being called
     * @returns Array of TypedCallArgument with the lambda's parameter types
     */
    protected buildLambdaCallArguments(
        convertedArgs: TypedExpression[],
        lambdaType: CustomLambdaType
    ): TypedCallArgument[] {
        return convertedArgs.map((arg, index) => {
            const paramType =
                index < lambdaType.details.parameterTypes.length
                    ? this.getTypeIndexForType(lambdaType.details.parameterTypes[index]!)
                    : arg.evalType;
            return { value: arg, parameterType: paramType };
        });
    }
}
