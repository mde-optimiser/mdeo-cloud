import {
    createRule,
    WS,
    ML_COMMENT,
    SL_COMMENT,
    HIDDEN_NEWLINE,
    ID,
    STRING,
    NEWLINE,
    or,
    many,
    optional,
    ref,
    group,
    type ParserRule
} from "@mdeo/language-common";
import { generateExpressionRules, generateTypeRules, type BaseExpressionType } from "@mdeo/language-expression";
import { Class, Property } from "@mdeo/language-metamodel";
import { LeadingTrailing, manySep } from "@mdeo/language-shared";
import {
    ModelTransformation,
    MetamodelFileImport,
    Pattern,
    PatternVariable,
    PatternVariableReassignment,
    PatternObjectInstance,
    PatternObjectInstanceDelete,
    PatternPropertyAssignment,
    PatternLink,
    PatternLinkEnd,
    PatternModifier,
    WhereClause,
    MatchStatement,
    IfMatchStatement,
    IfMatchConditionAndBlock,
    WhileMatchStatement,
    UntilMatchStatement,
    ForMatchStatement,
    IfExpressionStatement,
    ElseIfBranch,
    WhileExpressionStatement,
    StopStatement,
    StatementsScope,
    LambdaExpression,
    LambdaParameter,
    LambdaParameters,
    typeConfig,
    typeTypes,
    expressionConfig,
    expressionTypes,
    type ModelTransformationType,
    BaseTransformationStatement,
    PatternObjectInstanceReference
} from "./modelTransformationTypes.js";

/**
 * Generates the Model Transformation language rules.
 *
 * @returns The root rule and additional expression rules for the transformation language.
 */
export function generateModelTransformationRules(): {
    rule: ParserRule<ModelTransformationType>;
} {
    const { typeRule: TypeRule } = generateTypeRules(typeConfig, typeTypes);

    const additionalExpressionRules: ParserRule<BaseExpressionType>[] = [];

    /**
     * Lambda parameter rule.
     * Matches a single parameter name.
     */
    const LambdaParameterRule = createRule("LambdaParameterRule")
        .returns(LambdaParameter)
        .as(({ set }) => [set("name", ID)]);

    /**
     * Lambda parameters rule with parentheses.
     * Format: (param1, param2, ...)
     */
    const LambdaParametersRule = createRule("LambdaParametersRule")
        .returns(LambdaParameters)
        .as(({ add }) => ["(", ...manySep(add("parameters", LambdaParameterRule), ",", LeadingTrailing.TRAILING), ")"]);

    /**
     * Lambda expression rule with expression body only.
     * Format: (params) => expression
     */
    const LambdaExpressionRule = createRule("LambdaExpressionRule")
        .returns(LambdaExpression)
        .as(({ set }) => [set("parameterList", LambdaParametersRule), "=>", set("expression", () => ExpressionRule)]);

    additionalExpressionRules.push(LambdaExpressionRule);

    const expressionRules = generateExpressionRules(
        expressionConfig,
        expressionTypes,
        TypeRule,
        additionalExpressionRules
    );

    const ExpressionRule = expressionRules.expressionRule;

    /**
     * Pattern modifier rule.
     * Matches create, delete, forbid, or require keywords.
     */
    const PatternModifierRule = createRule("PatternModifierRule")
        .returns(PatternModifier)
        .as(({ set }) => [
            or(
                set("modifier", "create"),
                set("modifier", "delete"),
                set("modifier", "forbid"),
                set("modifier", "require")
            )
        ]);

    /**
     * Pattern variable rule.
     * Format: var name[: type] = expression
     */
    const PatternVariableRule = createRule("PatternVariableRule")
        .returns(PatternVariable)
        .as(({ set }) => [
            "var",
            set("name", ID),
            optional(group(":", set("type", TypeRule))),
            "=",
            set("value", ExpressionRule)
        ]);

    /**
     * Pattern variable reassignment rule.
     * Format: name = expression
     *
     * `name` must refer to a variable declared in an enclosing scope. The reassignment is
     * distinguished from a declaration by the absence of the `var` keyword and from an
     * object-instance reference (`name { ... }`) or typed instance (`name: Class`) by the
     * `=` operator that follows the identifier.
     */
    const PatternVariableReassignmentRule = createRule("PatternVariableReassignmentRule")
        .returns(PatternVariableReassignment)
        .as(({ set }) => [set("variable", ref(PatternVariable, ID)), "=", set("value", ExpressionRule)]);

    /**
     * Pattern property assignment rule.
     * Format: property op value, where op is one of: =, ==, !=, <, >, <=, >=
     * The assignment operator (=) sets a property value; comparison operators
     * (==, !=, <, >, <=, >=) constrain matching. Multi-character operators are
     * listed before single-character ones to avoid prefix mismatches.
     * Uses expression for value to support complex expressions.
     */
    const PatternPropertyAssignmentRule = createRule("PatternPropertyAssignmentRule")
        .returns(PatternPropertyAssignment)
        .as(({ set }) => [
            set("name", ref(Property, ID)),
            or(
                set("operator", "=="),
                set("operator", "!="),
                set("operator", "<="),
                set("operator", ">="),
                set("operator", "<"),
                set("operator", ">"),
                set("operator", "=")
            ),
            set("value", ExpressionRule)
        ]);

    /**
     * Pattern object instance rule.
     * Format: [modifier] name: Class { properties }
     */
    const PatternObjectInstanceRule = createRule("PatternObjectInstanceRule")
        .returns(PatternObjectInstance)
        .as(({ set, add }) => [
            optional(set("modifier", PatternModifierRule)),
            set("name", ID),
            ":",
            set("class", ref(Class, ID)),
            "{",
            many(or(add("properties", PatternPropertyAssignmentRule), NEWLINE)),
            "}"
        ]);

    /**
     * Gets the scope for property references in pattern object instances.
     * Traverses the class chain and looks up all properties for each class.
     */
    const PatternObjectInstanceReferenceRule = createRule("PatternObjectInstanceReferenceRule")
        .returns(PatternObjectInstanceReference)
        .as(({ set, add }) => [
            set("instance", ref(PatternObjectInstance, ID)),
            "{",
            many(or(add("properties", PatternPropertyAssignmentRule), NEWLINE)),
            "}"
        ]);

    /**
     * Pattern object delete rule.
     * Format: delete name
     * Deletes an already named node from a previous match.
     */
    const PatternObjectDeleteRule = createRule("PatternObjectDeleteRule")
        .returns(PatternObjectInstanceDelete)
        .as(({ set }) => ["delete", set("instance", ref(PatternObjectInstance, ID))]);

    /**
     * Pattern link end rule.
     * Format: object[.property]
     */
    const PatternLinkEndRule = createRule("PatternLinkEndRule")
        .returns(PatternLinkEnd)
        .as(({ set }) => [
            set("object", ref(PatternObjectInstance, ID)),
            optional(group(".", set("property", ref(Property, ID))))
        ]);

    /**
     * Pattern link rule.
     * Format: [modifier] source -- target
     */
    const PatternLinkRule = createRule("PatternLinkRule")
        .returns(PatternLink)
        .as(({ set }) => [
            optional(set("modifier", PatternModifierRule)),
            set("source", PatternLinkEndRule),
            "--",
            set("target", PatternLinkEndRule)
        ]);

    /**
     * Where clause rule.
     * Format: where expression
     */
    const WhereClauseRule = createRule("WhereClauseRule")
        .returns(WhereClause)
        .as(({ set }) => ["where", set("expression", ExpressionRule)]);

    /**
     * Pattern rule containing pattern elements.
     */
    const PatternRule = createRule("PatternRule")
        .returns(Pattern)
        .as(({ add }) => [
            "{",
            many(
                or(
                    add("elements", PatternVariableRule),
                    add("elements", PatternVariableReassignmentRule),
                    add("elements", PatternObjectInstanceReferenceRule),
                    add("elements", PatternObjectInstanceRule),
                    add("elements", PatternLinkRule),
                    add("elements", PatternObjectDeleteRule),
                    add("elements", WhereClauseRule),
                    NEWLINE
                )
            ),
            "}"
        ]);

    /**
     * Statements scope rule containing transformation statements.
     */
    const StatementsScopeRule = createRule("StatementsScopeRule")
        .returns(StatementsScope)
        .as(({ add }) => [
            "{",
            many(
                or(
                    add("statements", () => StatementRule),
                    NEWLINE
                )
            ),
            "}"
        ]);

    /**
     * Match statement rule.
     * Format: match { pattern }
     */
    const MatchStatementRule = createRule("MatchStatementRule")
        .returns(MatchStatement)
        .as(({ set }) => ["match", set("pattern", PatternRule)]);

    /**
     * If-match condition rule (inner node for scoping).
     * Format: match { pattern } then { block }
     */
    const IfMatchConditionRule = createRule("IfMatchConditionRule")
        .returns(IfMatchConditionAndBlock)
        .as(({ set }) => ["match", set("pattern", PatternRule), "then", set("thenBlock", () => StatementsScopeRule)]);

    /**
     * If-match statement rule.
     * Format: if match { pattern } then { block } [else { block }]
     */
    const IfMatchStatementRule = createRule("IfMatchStatementRule")
        .returns(IfMatchStatement)
        .as(({ set }) => [
            "if",
            set("ifBlock", IfMatchConditionRule),
            optional(
                group(
                    "else",
                    set("elseBlock", () => StatementsScopeRule)
                )
            )
        ]);

    /**
     * While-match statement rule.
     * Format: while match { pattern } do { block }
     */
    const WhileMatchStatementRule = createRule("WhileMatchStatementRule")
        .returns(WhileMatchStatement)
        .as(({ set }) => [
            "while",
            "match",
            set("pattern", PatternRule),
            "do",
            set("doBlock", () => StatementsScopeRule)
        ]);

    /**
     * Until-match statement rule.
     * Format: until match { pattern } do { block }
     */
    const UntilMatchStatementRule = createRule("UntilMatchStatementRule")
        .returns(UntilMatchStatement)
        .as(({ set }) => [
            "until",
            "match",
            set("pattern", PatternRule),
            "do",
            set("doBlock", () => StatementsScopeRule)
        ]);

    /**
     * For-match statement rule.
     * Format: for match { pattern } do { block }
     */
    const ForMatchStatementRule = createRule("ForMatchStatementRule")
        .returns(ForMatchStatement)
        .as(({ set }) => [
            "for",
            "match",
            set("pattern", PatternRule),
            "do",
            set("doBlock", () => StatementsScopeRule)
        ]);

    /**
     * Else-if branch rule.
     * Format: else if (condition) { block }
     */
    const ElseIfBranchRule = createRule("ElseIfBranchRule")
        .returns(ElseIfBranch)
        .as(({ set }) => [
            "else",
            "if",
            "(",
            set("condition", ExpressionRule),
            ")",
            set("block", () => StatementsScopeRule)
        ]);

    /**
     * If-expression statement rule.
     * Format: if (expression) { block } [[else if (expression) { block }]* else { block }]
     */
    const IfExpressionStatementRule = createRule("IfExpressionStatementRule")
        .returns(IfExpressionStatement)
        .as(({ set, add }) => [
            "if",
            "(",
            set("condition", ExpressionRule),
            ")",
            set("thenBlock", () => StatementsScopeRule),
            many(add("elseIfBranches", ElseIfBranchRule)),
            optional(
                group(
                    "else",
                    set("elseBlock", () => StatementsScopeRule)
                )
            )
        ]);

    /**
     * While-expression statement rule.
     * Format: while (expression) { block }
     */
    const WhileExpressionStatementRule = createRule("WhileExpressionStatementRule")
        .returns(WhileExpressionStatement)
        .as(({ set }) => [
            "while",
            "(",
            set("condition", ExpressionRule),
            ")",
            set("block", () => StatementsScopeRule)
        ]);

    /**
     * Stop statement rule.
     * Format: kill or stop
     */
    const StopStatementRule = createRule("StopStatementRule")
        .returns(StopStatement)
        .as(({ set }) => [or(set("keyword", "kill"), set("keyword", "stop"))]);

    /**
     * Statement rule containing transformation statements.
     */
    const StatementRule = createRule("StatementRule")
        .returns(BaseTransformationStatement)
        .as(() => [
            or(
                MatchStatementRule,
                IfMatchStatementRule,
                WhileMatchStatementRule,
                UntilMatchStatementRule,
                ForMatchStatementRule,
                IfExpressionStatementRule,
                WhileExpressionStatementRule,
                StopStatementRule
            )
        ]);

    /**
     * Metamodel file import rule.
     * Format: using "filename"
     */
    const MetamodelFileImportRule = createRule("MetamodelFileImportRule")
        .returns(MetamodelFileImport)
        .as(({ set }) => ["using", set("file", STRING)]);

    /**
     * Root rule for Model Transformation language.
     */
    const ModelTransformationRule = createRule("ModelTransformationRule")
        .returns(ModelTransformation)
        .as(({ add, set }) => [
            many(NEWLINE),
            set("import", MetamodelFileImportRule),
            many(or(add("statements", StatementRule), NEWLINE))
        ]);

    return {
        rule: ModelTransformationRule
    };
}

/**
 * Additional terminals for the Model Transformation language.
 */
export const ModelTransformationTerminals = [WS, HIDDEN_NEWLINE, ML_COMMENT, SL_COMMENT];
