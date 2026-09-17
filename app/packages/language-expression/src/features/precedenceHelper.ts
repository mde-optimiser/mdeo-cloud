import type { ExpressionTypes, BinaryExpressionType, BaseExpressionType } from "../grammar/expressionTypes.js";

/**
 * Precedence levels for expressions (lower number = higher precedence/binds tighter).
 * Based on the grammar rules in expressionRule.ts:
 * - Primary expressions (literals, identifiers, parenthesized expressions): 0
 * - Postfix expressions (member access, call, assert non-null): 1
 * - Unary expressions (!, -): 2
 * - Type cast expressions: 3
 * - Multiplicative binary operators (*, /, %): 4
 * - Additive binary operators (+, -): 5
 * - Null-coalescing binary operator (??): 6
 * - Type check expressions (is, as): 7
 * - Relational binary operators (<, >, <=, >=): 8
 * - Equality binary operators (==, !=): 9
 * - Logical AND (&&): 10
 * - Logical OR (||): 11
 * - Ternary conditional (?:): 12
 */
export const enum Precedence {
    PRIMARY = 0,
    POSTFIX = 1,
    UNARY = 2,
    TYPE_CAST = 3,
    MULTIPLICATIVE = 4,
    ADDITIVE = 5,
    NULL_COALESCING = 6,
    TYPE_CHECK = 7,
    RELATIONAL = 8,
    EQUALITY = 9,
    LOGICAL_AND = 10,
    LOGICAL_OR = 11,
    TERNARY = 12
}

/**
 * Gets the precedence level of a binary operator.
 *
 * @param operator The binary operator
 * @returns The precedence level
 */
function getBinaryOperatorPrecedence(operator: string): Precedence {
    switch (operator) {
        case "*":
        case "/":
        case "%":
            return Precedence.MULTIPLICATIVE;
        case "+":
        case "-":
            return Precedence.ADDITIVE;
        case "??":
            return Precedence.NULL_COALESCING;
        case "<":
        case ">":
        case "<=":
        case ">=":
            return Precedence.RELATIONAL;
        case "==":
        case "!=":
        case "===":
        case "!==":
            return Precedence.EQUALITY;
        case "&&":
            return Precedence.LOGICAL_AND;
        case "||":
            return Precedence.LOGICAL_OR;
        default:
            return Precedence.PRIMARY;
    }
}

/**
 * Determines the precedence level of an expression AST node.
 *
 * @template T The type of the AST node
 * @param node The AST node
 * @param types The generated expression types
 * @returns The precedence level
 */
export function getExpressionPrecedence<T extends BaseExpressionType>(node: T, types: ExpressionTypes): Precedence {
    const nodeType = node.$type;

    if (
        nodeType === types.stringLiteralExpressionType.name ||
        nodeType === types.intLiteralExpressionType.name ||
        nodeType === types.longLiteralExpressionType.name ||
        nodeType === types.floatLiteralExpressionType.name ||
        nodeType === types.doubleLiteralExpressionType.name ||
        nodeType === types.booleanLiteralExpressionType.name ||
        nodeType === types.nullLiteralExpressionType.name ||
        nodeType === types.identifierExpressionType.name
    ) {
        return Precedence.PRIMARY;
    }

    if (
        nodeType === types.memberAccessExpressionType.name ||
        nodeType === types.callExpressionType.name ||
        nodeType === types.memberCallExpressionType.name ||
        nodeType === types.assertNonNullExpressionType.name
    ) {
        return Precedence.POSTFIX;
    }

    if (nodeType === types.unaryExpressionType.name) {
        return Precedence.UNARY;
    }

    if (nodeType === types.typeCastExpressionType.name) {
        return Precedence.TYPE_CAST;
    }

    if (nodeType === types.typeCheckExpressionType.name) {
        return Precedence.TYPE_CHECK;
    }

    if (nodeType === types.binaryExpressionType.name) {
        const binaryNode = node as BinaryExpressionType;
        return getBinaryOperatorPrecedence(binaryNode.operator);
    }

    if (nodeType === types.ternaryExpressionType.name) {
        return Precedence.TERNARY;
    }

    return Precedence.PRIMARY;
}
