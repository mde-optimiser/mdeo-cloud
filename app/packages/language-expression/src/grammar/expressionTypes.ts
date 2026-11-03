import { createInterface, Optional, Union, type ASTType } from "@mdeo/language-common";
import type { ExpressionConfig } from "./expressionConfig.js";
import type { TypeTypes } from "./typeTypes.js";

/**
 * Generates expression-related type interfaces based on the provided configuration.
 *
 * @param config config used for generating type names
 * @param typeTypes the generated type types to reference in expressions
 * @returns the generated expression types
 */
export function generateExpressionTypes(config: ExpressionConfig, typeTypes: TypeTypes) {
    const baseTypeType = typeTypes.baseTypeType;
    const classTypeType = typeTypes.classTypeType;
    const lambdaTypeType = typeTypes.lambdaTypeType;

    const baseExpressionType = createInterface(config.baseExpressionTypeName).attrs({});

    const assertNonNullExpressionType = createInterface(config.assertNonNullExpressionTypeName)
        .extends(baseExpressionType)
        .attrs({
            expression: baseExpressionType
        });

    const unaryExpressionType = createInterface(config.unaryExpressionTypeName)
        .extends(baseExpressionType)
        .attrs({
            expression: baseExpressionType,
            operator: Union("-", "!")
        });

    const binaryExpressionType = createInterface(config.binaryExpressionTypeName)
        .extends(baseExpressionType)
        .attrs({
            operator: Union("+", "-", "*", "/", "%", "??", "&&", "||", "==", "!=", "===", "!==", "<", ">", "<=", ">="),
            left: baseExpressionType,
            right: baseExpressionType
        });

    const ternaryExpressionType = createInterface(config.ternaryExpressionTypeName).extends(baseExpressionType).attrs({
        condition: baseExpressionType,
        trueExpression: baseExpressionType,
        falseExpression: baseExpressionType
    });

    const typeCastExpressionType = createInterface(config.typeCastExpressionTypeName)
        .extends(baseExpressionType)
        .attrs({
            expression: baseExpressionType,
            targetType: baseTypeType,
            isSafe: Boolean
        });

    const typeCheckExpressionType = createInterface(config.typeCheckExpressionTypeName)
        .extends(baseExpressionType)
        .attrs({
            expression: baseExpressionType,
            checkType: baseTypeType,
            isNegated: Boolean
        });

    const callExpressionGenericArgsType = createInterface(config.callExpressionGenericArgsTypeName).attrs({
        typeArguments: [baseTypeType]
    });

    const namedArgumentType = createInterface(config.namedArgumentTypeName).attrs({
        name: String,
        value: baseExpressionType
    });

    const callExpressionType = createInterface(config.callExpressionTypeName)
        .extends(baseExpressionType)
        .attrs({
            expression: baseExpressionType,
            arguments: [baseExpressionType],
            namedArguments: [namedArgumentType],
            genericArgs: Optional(callExpressionGenericArgsType)
        });

    const memberCallExpressionType = createInterface(config.memberCallExpressionTypeName)
        .extends(baseExpressionType)
        .attrs({
            expression: baseExpressionType,
            member: String,
            isNullChaining: Boolean,
            arguments: [baseExpressionType],
            namedArguments: [namedArgumentType],
            genericArgs: Optional(callExpressionGenericArgsType)
        });

    const memberAccessExpressionType = createInterface(config.memberAccessExpressionTypeName)
        .extends(baseExpressionType)
        .attrs({
            expression: baseExpressionType,
            member: String,
            isNullChaining: Boolean
        });

    const identifierExpressionType = createInterface(config.identifierExpressionTypeName)
        .extends(baseExpressionType)
        .attrs({
            name: String
        });

    const stringLiteralExpressionType = createInterface(config.stringLiteralExpressionTypeName)
        .extends(baseExpressionType)
        .attrs({
            value: String
        });

    const intLiteralExpressionType = createInterface(config.intLiteralExpressionTypeName)
        .extends(baseExpressionType)
        .attrs({
            value: String
        });

    const longLiteralExpressionType = createInterface(config.longLiteralExpressionTypeName)
        .extends(baseExpressionType)
        .attrs({
            value: String
        });

    const floatLiteralExpressionType = createInterface(config.floatLiteralExpressionTypeName)
        .extends(baseExpressionType)
        .attrs({
            value: String
        });

    const doubleLiteralExpressionType = createInterface(config.doubleLiteralExpressionTypeName)
        .extends(baseExpressionType)
        .attrs({
            value: String
        });

    const booleanLiteralExpressionType = createInterface(config.booleanLiteralExpressionTypeName)
        .extends(baseExpressionType)
        .attrs({
            value: Boolean
        });

    const nullLiteralExpressionType = createInterface(config.nullLiteralExpressionTypeName)
        .extends(baseExpressionType)
        .attrs({});

    const listExpressionType = createInterface(config.listExpressionTypeName)
        .extends(baseExpressionType)
        .attrs({
            elements: [baseExpressionType]
        });

    return {
        baseExpressionType,
        unaryExpressionType,
        binaryExpressionType,
        ternaryExpressionType,
        callExpressionGenericArgsType,
        namedArgumentType,
        callExpressionType,
        memberCallExpressionType,
        memberAccessExpressionType,
        identifierExpressionType,
        stringLiteralExpressionType,
        intLiteralExpressionType,
        longLiteralExpressionType,
        floatLiteralExpressionType,
        doubleLiteralExpressionType,
        booleanLiteralExpressionType,
        nullLiteralExpressionType,
        baseTypeType,
        classTypeType,
        lambdaTypeType,
        assertNonNullExpressionType,
        typeCastExpressionType,
        typeCheckExpressionType,
        listExpressionType
    };
}

/**
 * Type representing all generated expression types.
 */
export type ExpressionTypes = ReturnType<typeof generateExpressionTypes>;

/**
 * Type representing the base expression type.
 */
export type BaseExpressionType = ASTType<ExpressionTypes["baseExpressionType"]>;

/**
 * Type representing the unary expression type.
 */
export type UnaryExpressionType = ASTType<ExpressionTypes["unaryExpressionType"]>;

/**
 * Type representing the binary expression type.
 */
export type BinaryExpressionType = ASTType<ExpressionTypes["binaryExpressionType"]>;

/**
 * Type representing the ternary expression type.
 */
export type TernaryExpressionType = ASTType<ExpressionTypes["ternaryExpressionType"]>;

/**
 * Type representing the call expression generic args type.
 */
export type CallExpressionGenericArgsType = ASTType<ExpressionTypes["callExpressionGenericArgsType"]>;

/**
 * Type representing a named argument of a call, `name = value`.
 */
export type NamedArgumentType = ASTType<ExpressionTypes["namedArgumentType"]>;

/**
 * Type representing the call expression
 */
export type CallExpressionType = ASTType<ExpressionTypes["callExpressionType"]>;

/**
 * Type representing the member call expression
 */
export type MemberCallExpressionType = ASTType<ExpressionTypes["memberCallExpressionType"]>;

/**
 * Type representing the member access expression
 */
export type MemberAccessExpressionType = ASTType<ExpressionTypes["memberAccessExpressionType"]>;

/**
 * Type representing the identifier expression type.
 */
export type IdentifierExpressionType = ASTType<ExpressionTypes["identifierExpressionType"]>;

/**
 * Type representing the string literal expression type.
 */
export type StringLiteralExpressionType = ASTType<ExpressionTypes["stringLiteralExpressionType"]>;

/**
 * Type representing the int literal expression type.
 */
export type IntLiteralExpressionType = ASTType<ExpressionTypes["intLiteralExpressionType"]>;

/**
 * Type representing the long literal expression type.
 */
export type LongLiteralExpressionType = ASTType<ExpressionTypes["longLiteralExpressionType"]>;

/**
 * Type representing the float literal expression type.
 */
export type FloatLiteralExpressionType = ASTType<ExpressionTypes["floatLiteralExpressionType"]>;

/**
 * Type representing the double literal expression type.
 */
export type DoubleLiteralExpressionType = ASTType<ExpressionTypes["doubleLiteralExpressionType"]>;

/**
 * Type representing the boolean literal expression type.
 */
export type BooleanLiteralExpressionType = ASTType<ExpressionTypes["booleanLiteralExpressionType"]>;

/**
 * Type representing the null literal expression type.
 */
export type NullLiteralExpressionType = ASTType<ExpressionTypes["nullLiteralExpressionType"]>;

/**
 * Type representing the assert non-null expression (!! postfix operator).
 */
export type AssertNonNullExpressionType = ASTType<ExpressionTypes["assertNonNullExpressionType"]>;

/**
 * Type representing the type cast expression (as / as?).
 */
export type TypeCastExpressionType = ASTType<ExpressionTypes["typeCastExpressionType"]>;

/**
 * Type representing the type check expression (is / !is).
 */
export type TypeCheckExpressionType = ASTType<ExpressionTypes["typeCheckExpressionType"]>;

/**
 * Type representing the list expression (square brackets with comma separated values).
 */
export type ListExpressionType = ASTType<ExpressionTypes["listExpressionType"]>;
