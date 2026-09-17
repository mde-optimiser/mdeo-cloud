import {
    action,
    createFragmentRule,
    createInfixRule,
    createRule,
    createTerminal,
    group,
    ID,
    many,
    optional,
    or,
    STRING,
    treeRewriteAction,
    type ParserRule,
    type RuleEntry
} from "@mdeo/language-common";
import { LeadingTrailing, manySep } from "@mdeo/language-shared";
import type { ExpressionConfig } from "./expressionConfig.js";
import type { BaseExpressionType, ExpressionTypes } from "./expressionTypes.js";
import type { BaseTypeType } from "./typeTypes.js";

/**
 * Integer token (32-bit) - returns string to preserve precision
 */
export const INT_LITERAL = createTerminal("INT_LITERAL")
    .returns(String)
    .as(/[0-9]+/);

/**
 * Long token (64-bit integer, suffixed with L or l) - returns string to preserve precision
 */
export const LONG_LITERAL = createTerminal("LONG_LITERAL")
    .returns(String)
    .as(/[0-9]+[Ll]/);

/**
 * Float token (32-bit floating point, suffixed with F or f) - returns string to preserve precision
 */
export const FLOAT_LITERAL = createTerminal("FLOAT_LITERAL")
    .returns(String)
    .as(/[0-9]+\.[0-9]+[Ff]/);

/**
 * Double token (64-bit floating point, optionally suffixed with D or d) - returns string to preserve precision
 */
export const DOUBLE_LITERAL = createTerminal("DOUBLE_LITERAL")
    .returns(String)
    .as(/[0-9]+\.[0-9]+[Dd]?/);

/**
 * Boolean token (technically a data type rule)
 */
const BOOLEAN = createRule("BOOLEAN")
    .returns(Boolean)
    .as(() => [or("true", "false")]);

/**
 * Options for the generated expression rules.
 */
export interface ExpressionRuleOptions {
    /**
     * Whether calls accept named arguments, `f(a, name = b)`.
     */
    namedArguments?: boolean;
}

/**
 * Generates expression-related parser rules based on the provided configuration.
 *
 * This function creates a complete set of parser rules for common programming language
 * expressions including literals (string, number, boolean), identifiers, member access,
 * function calls with generic arguments, unary operations, binary operations with proper
 * precedence, and ternary conditional expressions. The generated rules follow standard
 * operator precedence and support extensibility through additional custom expression rules.
 *
 * CAUTION: The additionalExpressionRules is not used immediately, it will be used when the ExpressionRule is actually resolved.
 * Thus, it can be safely modified even after this function returns.
 * This can be used to solve circular dependencies between rules.
 *
 * @param config Configuration object containing naming for all expression rules and types
 * @param types The generated expression type interfaces to use as return types
 * @param typeRule The type parser rule to use for generic type arguments
 * @param additionalExpressionRules Optional array of custom expression rules to include
 * @param options Options enabling optional syntax
 * @returns The top-level expression parser rule
 */
export function generateExpressionRules(
    config: ExpressionConfig,
    types: ExpressionTypes,
    typeRule: ParserRule<BaseTypeType>,
    additionalExpressionRules: ParserRule<BaseExpressionType>[],
    options: ExpressionRuleOptions = {}
) {
    const stringLiteralExpressionRule = createRule(config.stringLiteralExpressionRuleName)
        .returns(types.stringLiteralExpressionType)
        .as(({ set }) => [set("value", STRING)]);

    const intLiteralExpressionRule = createRule(config.intLiteralExpressionRuleName)
        .returns(types.intLiteralExpressionType)
        .as(({ set }) => [set("value", INT_LITERAL)]);

    const longLiteralExpressionRule = createRule(config.longLiteralExpressionRuleName)
        .returns(types.longLiteralExpressionType)
        .as(({ set }) => [set("value", LONG_LITERAL)]);

    const floatLiteralExpressionRule = createRule(config.floatLiteralExpressionRuleName)
        .returns(types.floatLiteralExpressionType)
        .as(({ set }) => [set("value", FLOAT_LITERAL)]);

    const doubleLiteralExpressionRule = createRule(config.doubleLiteralExpressionRuleName)
        .returns(types.doubleLiteralExpressionType)
        .as(({ set }) => [set("value", DOUBLE_LITERAL)]);

    const booleanLiteralExpressionRule = createRule(config.booleanLiteralExpressionRuleName)
        .returns(types.booleanLiteralExpressionType)
        .as(({ set }) => [set("value", BOOLEAN)]);

    const nullLiteralExpressionRule = createRule(config.nullLiteralExpressionTypeName)
        .returns(types.nullLiteralExpressionType)
        .as(() => [action(types.nullLiteralExpressionType, () => ["null"])]);

    const identifierExpressionRule = createRule(config.identifierExpressionRuleName)
        .returns(types.identifierExpressionType)
        .as(({ set }) => [set("name", ID)]);

    const listExpressionRule = createRule(config.listExpressionRuleName)
        .returns(types.listExpressionType)
        .as(({ add }) => [
            "[",
            ...manySep(
                add("elements", () => expressionRule),
                ",",
                LeadingTrailing.TRAILING
            ),
            "]"
        ]);

    const primaryExpressionRule = createRule(config.primaryExpressionRuleName)
        .returns(types.baseExpressionType)
        .as(() => {
            return [
                or(
                    stringLiteralExpressionRule,
                    intLiteralExpressionRule,
                    longLiteralExpressionRule,
                    floatLiteralExpressionRule,
                    doubleLiteralExpressionRule,
                    booleanLiteralExpressionRule,
                    nullLiteralExpressionRule,
                    identifierExpressionRule,
                    listExpressionRule,
                    ...additionalExpressionRules,
                    group("(", () => expressionRule, ")")
                )
            ];
        });
    const assertNonNullPostfixFragment = createFragmentRule(config.assertNonNullPostfixFragmentRuleName)
        .returns(types.baseExpressionType)
        .as(() => [treeRewriteAction(types.assertNonNullExpressionType, "expression", "=", () => ["!!"])]);

    const memberAccessPostfixFragment = createFragmentRule(config.memberAccessPostfixFragmentRuleName)
        .returns(types.baseExpressionType)
        .as(() => [
            treeRewriteAction(types.memberAccessExpressionType, "expression", "=", ({ set, flag }) => [
                or(flag("isNullChaining", "?."), "."),
                set("member", ID)
            ])
        ]);

    const namedArgumentRule = createRule(config.namedArgumentRuleName)
        .returns(types.namedArgumentType)
        .as(({ set }) => [set("name", ID), "=", set("value", () => expressionRule)]);

    // Positional and named arguments may be written in any order here; that positional arguments
    // come first is checked by validation, which can say so, rather than by a parse error.
    const callArguments = (positional: RuleEntry, named: RuleEntry) =>
        manySep(options.namedArguments === true ? or(named, positional) : positional, ",", LeadingTrailing.TRAILING);

    const callPostfixFragment = createFragmentRule(config.callPostfixFragmentRuleName)
        .returns(types.baseExpressionType)
        .as(() => [
            treeRewriteAction(types.callExpressionType, "expression", "=", ({ set, add }) => [
                set("genericArgs", callExpressionGenericArgsRule),
                "(",
                ...callArguments(
                    add("arguments", () => expressionRule),
                    add("namedArguments", namedArgumentRule)
                ),
                ")"
            ])
        ]);

    const memberCallPostfixFragment = createFragmentRule(config.memberCallPostfixFragmentRuleName)
        .returns(types.baseExpressionType)
        .as(() => [
            treeRewriteAction(types.memberCallExpressionType, "expression", "=", ({ set, add, flag }) => [
                or(flag("isNullChaining", "?."), "."),
                set("member", ID),
                set("genericArgs", callExpressionGenericArgsRule),
                "(",
                ...callArguments(
                    add("arguments", () => expressionRule),
                    add("namedArguments", namedArgumentRule)
                ),
                ")"
            ])
        ]);

    const postfixExpressionRule = createRule(config.postfixExpressionRuleName)
        .returns(types.baseExpressionType)
        .as(() => [
            primaryExpressionRule,
            many(
                or(
                    memberAccessPostfixFragment,
                    callPostfixFragment,
                    memberCallPostfixFragment,
                    assertNonNullPostfixFragment
                )
            )
        ]);

    const callExpressionGenericArgsRule = createRule(config.callExpressionGenericArgsRuleName)
        .returns(types.callExpressionGenericArgsType)
        .as(({ add }) => [
            optional("<", ...manySep(add("typeArguments", typeRule), ",", LeadingTrailing.TRAILING), ">")
        ]);

    const unaryExpressionRule = createRule(config.unaryExpressionRuleName)
        .returns(types.baseExpressionType)
        .as(() => [
            or(
                postfixExpressionRule,
                action(types.unaryExpressionType, ({ set }) => [
                    set("operator", "!", "-"),
                    set("expression", postfixExpressionRule)
                ])
            )
        ]);

    const typeCastExpressionRule = createRule(config.typeCastExpressionRuleName)
        .returns(types.baseExpressionType)
        .as(() => [
            unaryExpressionRule,
            many(
                treeRewriteAction(types.typeCastExpressionType, "expression", "=", ({ set, flag }) => [
                    or(flag("isSafe", "as?"), "as"),
                    set("targetType", typeRule)
                ])
            )
        ]);

    const binaryExpressionUpperRule = createInfixRule(config.binaryExpressionUpperRuleName)
        .on(typeCastExpressionRule)
        .returns(types.binaryExpressionType)
        .operators("*", "/", "%")
        .operators("+", "-")
        .operators("??")
        .build();

    const typeCheckExpressionRule = createRule(config.typeCheckExpressionRuleName)
        .returns(types.baseExpressionType)
        .as(() => [
            binaryExpressionUpperRule,
            many(
                treeRewriteAction(types.typeCheckExpressionType, "expression", "=", ({ set, flag }) => [
                    or(flag("isNegated", "!is"), "is"),
                    set("checkType", typeRule)
                ])
            )
        ]);

    const binaryExpressionLowerRule = createInfixRule(config.binaryExpressionLowerRuleName)
        .on(typeCheckExpressionRule)
        .returns(types.binaryExpressionType)
        .operators("<", ">", "<=", ">=")
        .operators("===", "!==", "==", "!=")
        .operators("&&")
        .operators("||")
        .build();

    const binaryExpressionRule = createRule(config.binaryExpressionRuleName)
        .returns(types.baseExpressionType)
        .as(() => [binaryExpressionLowerRule]);

    const ternaryExpressionRule = createRule(config.ternaryExpressionRuleName)
        .returns(types.baseExpressionType)
        .as(() => [
            binaryExpressionRule,
            many(
                treeRewriteAction(types.ternaryExpressionType, "condition", "=", ({ set }) => [
                    "?",
                    set("trueExpression", () => ternaryExpressionRule),
                    ":",
                    set("falseExpression", () => ternaryExpressionRule)
                ])
            )
        ]);

    const expressionRule = createRule(config.expressionRuleName)
        .returns(types.baseExpressionType)
        .as(() => [ternaryExpressionRule]);

    return {
        expressionRule,
        stringLiteralExpressionRule,
        intLiteralExpressionRule,
        longLiteralExpressionRule,
        floatLiteralExpressionRule,
        doubleLiteralExpressionRule,
        booleanLiteralExpressionRule,
        nullLiteralExpressionRule,
        identifierExpressionRule,
        listExpressionRule,
        primaryExpressionRule,
        postfixExpressionRule,
        assertNonNullPostfixFragment,
        memberAccessPostfixFragment,
        callPostfixFragment,
        memberCallPostfixFragment,
        callExpressionGenericArgsRule,
        namedArgumentRule,
        unaryExpressionRule,
        typeCastExpressionRule,
        binaryExpressionUpperRule,
        typeCheckExpressionRule,
        binaryExpressionLowerRule,
        binaryExpressionRule,
        ternaryExpressionRule
    };
}
