import {
    action,
    createRule,
    group,
    ID,
    many,
    NEWLINE,
    optional,
    or,
    treeRewriteAction,
    type ParserRule
} from "@mdeo/language-common";
import type { StatementConfig } from "./statementConfig.js";
import type { BaseStatementType, StatementTypes } from "./statementTypes.js";
import type { BaseExpressionType } from "./expressionTypes.js";
import type { BaseTypeType } from "./typeTypes.js";

/**
 * Generates statement-related parser rules based on the provided configuration.
 *
 * This function creates a complete set of parser rules for common programming language
 * statements including control flow (if, while, do-while, for), variable declarations,
 * assignments, and expression statements. The generated rules are designed to work in
 * a newline-aware context where statements are separated by newlines rather than semicolons.
 *
 * @param config Configuration object containing naming for all statement rules and types
 * @param types The generated statement type interfaces to use as return types
 * @param expressionRule The expression parser rule to use for conditions, values, etc.
 * @param typeRule The type parser rule to use for variable type annotations
 * @param additionalStatementRules Optional array of custom statement rules to include
 * @returns Object containing all generated statement parser rules
 */
export function generateStatementRules(
    config: StatementConfig,
    types: StatementTypes,
    expressionRule: ParserRule<BaseExpressionType>,
    typeRule: ParserRule<BaseTypeType>,
    additionalStatementRules: ParserRule<BaseStatementType>[]
) {
    const statementsScopeRule: ParserRule<any> = createRule(config.statementsScopeRuleName)
        .returns(types.statementsScopeType)
        .as(({ add }) => [
            "{",
            many(
                or(
                    add("statements", () => statementRule),
                    NEWLINE
                )
            ),
            "}"
        ]);

    const elseIfClauseRule = createRule(config.elseIfClauseRuleName)
        .returns(types.elseIfClauseType)
        .as(({ set }) => [
            "else",
            "if",
            "(",
            set("condition", expressionRule),
            ")",
            set("thenBlock", statementsScopeRule)
        ]);

    const ifStatementRule = createRule(config.ifStatementRuleName)
        .returns(types.ifStatementType)
        .as(({ set, add }) => [
            "if",
            "(",
            set("condition", expressionRule),
            ")",
            set("thenBlock", statementsScopeRule),
            many(add("elseIfs", elseIfClauseRule)),
            optional("else", set("elseBlock", statementsScopeRule))
        ]);

    const whileStatementRule = createRule(config.whileStatementRuleName)
        .returns(types.whileStatementType)
        .as(({ set }) => ["while", "(", set("condition", expressionRule), ")", set("body", statementsScopeRule)]);

    const forStatementVariableDeclarationRule = createRule(config.forStatementVariableDeclarationRuleName)
        .returns(types.forStatementVariableDeclarationType)
        .as(({ set }) => [set("name", ID), optional(":", set("type", typeRule))]);

    const forStatementRule = createRule(config.forStatementRuleName)
        .returns(types.forStatementType)
        .as(({ set }) => [
            "for",
            "(",
            set("variable", forStatementVariableDeclarationRule),
            "in",
            set("iterable", expressionRule),
            ")",
            set("body", statementsScopeRule)
        ]);

    const variableDeclarationStatementRule = createRule(config.variableDeclarationStatementRuleName)
        .returns(types.variableDeclarationStatementType)
        .as(({ set, flag }) => [
            or(flag("isReadonly", "val"), "var"),
            set("name", ID),
            or(
                group(":", set("type", typeRule), optional("=", set("initialValue", expressionRule))),
                group("=", set("initialValue", expressionRule))
            )
        ]);

    const expressionStatementRule = createRule(config.expressionStatementRuleName)
        .returns(types.baseStatementType)
        .as(() => [
            expressionRule,
            or(
                treeRewriteAction(types.assignmentStatementType, "left", "=", ({ set }) => [
                    "=",
                    set("right", expressionRule)
                ]),
                treeRewriteAction(types.expressionStatementType, "expression", "=", () => [])
            )
        ]);

    const breakStatementRule = createRule(config.breakStatementRuleName)
        .returns(types.breakStatementType)
        .as(() => [action(types.breakStatementType, () => ["break"])]);

    const continueStatementRule = createRule(config.continueStatementRuleName)
        .returns(types.continueStatementType)
        .as(() => [action(types.continueStatementType, () => ["continue"])]);

    const statementRule = createRule(config.statementRuleName)
        .returns(types.baseStatementType)
        .as(() => [
            or(
                ifStatementRule,
                whileStatementRule,
                forStatementRule,
                variableDeclarationStatementRule,
                breakStatementRule,
                continueStatementRule,
                expressionStatementRule,
                ...additionalStatementRules
            )
        ]);

    return {
        statementRule,
        statementsScopeRule,
        ifStatementRule,
        elseIfClauseRule,
        whileStatementRule,
        forStatementRule,
        forStatementVariableDeclarationRule,
        variableDeclarationStatementRule,
        expressionStatementRule,
        breakStatementRule,
        continueStatementRule
    };
}
