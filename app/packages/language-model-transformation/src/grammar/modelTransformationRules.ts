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
    PatternObjectInstanceReference,
    PatternApplicationCondition,
    TransformationParameter,
    CallArgument,
    CallOutput,
    CallStatement,
    TransformationFileImport,
    AssignableVariable,
    ObjectInstanceDeclaration,
    PatternCollectionAppend,
    IfCallConditionAndBlock,
    IfCallStatement,
    ForInVariable,
    ForInStatement,
    AssignmentStatement,
    PatternModelScope,
    ModelValueDeclaration,
    CallableTransformation
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
     * Matches the create or delete keyword.
     *
     * `forbid` and `require` are not element modifiers; they introduce an application
     * condition block ({@link PatternApplicationConditionRule}).
     */
    const PatternModifierRule = createRule("PatternModifierRule")
        .returns(PatternModifier)
        .as(({ set }) => [or(set("modifier", "create"), set("modifier", "delete"))]);

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
        .as(({ set }) => [set("variable", ref(AssignableVariable, ID)), "=", set("value", ExpressionRule)]);

    /**
     * Pattern collection append rule.
     * Format: name += expression
     *
     * `name` must be a collection `out` parameter of the transformation.
     */
    const PatternCollectionAppendRule = createRule("PatternCollectionAppendRule")
        .returns(PatternCollectionAppend)
        .as(({ set }) => [set("variable", ref(AssignableVariable, ID)), "+=", set("value", ExpressionRule)]);

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
            set("instance", ref(ObjectInstanceDeclaration, ID)),
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
        .as(({ set }) => ["delete", set("instance", ref(ObjectInstanceDeclaration, ID))]);

    /**
     * Pattern link end rule.
     * Format: object[.property]
     */
    const PatternLinkEndRule = createRule("PatternLinkEndRule")
        .returns(PatternLinkEnd)
        .as(({ set }) => [
            set("object", ref(ObjectInstanceDeclaration, ID)),
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
     * Application condition rule.
     * Format: forbid|require [name] { elements }
     *
     * The block describes a graph of its own, matched independently of the enclosing
     * pattern and of every other block. Only structural elements are allowed inside it;
     * `var` and `delete <name>` are deliberately not part of the rule, so that a condition
     * always denotes a plain sub-graph. A model scope block puts part of the condition graph
     * into an input model; the validator rejects a condition block nested in another one
     * (through a scope block).
     */
    const PatternApplicationConditionRule: ParserRule<any> = createRule("PatternApplicationConditionRule")
        .returns(PatternApplicationCondition)
        .as(({ set, add }) => [
            or(set("kind", "forbid"), set("kind", "require")),
            optional(set("name", ID)),
            "{",
            many(
                or(
                    add("elements", PatternObjectInstanceReferenceRule),
                    add("elements", PatternObjectInstanceRule),
                    add("elements", PatternLinkRule),
                    add("elements", WhereClauseRule),
                    add("elements", () => PatternModelScopeRule),
                    NEWLINE
                )
            ),
            "}"
        ]);

    /**
     * Model scope block rule.
     * Format: in model { elements }
     *
     * The block's objects belong to the input model `model` (a `model` input or the variable
     * of a loop over a `List<model>` input). It contains what finds and reads objects; the
     * validator rejects whatever would change the (read-only) input model and nested scope
     * blocks.
     */
    const PatternModelScopeRule: ParserRule<any> = createRule("PatternModelScopeRule")
        .returns(PatternModelScope)
        .as(({ set, add }) => [
            "in",
            set("model", ref(ModelValueDeclaration, ID)),
            "{",
            many(
                or(
                    add("elements", PatternVariableRule),
                    add("elements", PatternObjectInstanceReferenceRule),
                    add("elements", PatternObjectInstanceRule),
                    add("elements", PatternLinkRule),
                    add("elements", PatternObjectDeleteRule),
                    add("elements", WhereClauseRule),
                    add("elements", PatternApplicationConditionRule),
                    add("elements", () => PatternModelScopeRule),
                    NEWLINE
                )
            ),
            "}"
        ]);

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
                    add("elements", PatternCollectionAppendRule),
                    add("elements", PatternObjectInstanceReferenceRule),
                    add("elements", PatternObjectInstanceRule),
                    add("elements", PatternLinkRule),
                    add("elements", PatternObjectDeleteRule),
                    add("elements", WhereClauseRule),
                    add("elements", PatternApplicationConditionRule),
                    add("elements", PatternModelScopeRule),
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
     * Call argument rule.
     * Format: parameter = expression
     */
    const CallArgumentRule = createRule("CallArgumentRule")
        .returns(CallArgument)
        .as(({ set }) => [set("parameter", ref(TransformationParameter, ID)), "=", set("value", ExpressionRule)]);

    /**
     * Call output rule.
     * Format: parameter: name
     */
    const CallOutputRule = createRule("CallOutputRule")
        .returns(CallOutput)
        .as(({ set }) => [set("parameter", ref(TransformationParameter, ID)), ":", set("name", ID)]);

    /**
     * Call statement rule.
     * Format: call alias(argument, ...) [-> (output, ...)]
     *
     * The alias is the one of a path import; the callee resolves to the imported file's root.
     */
    const CallStatementRule = createRule("CallStatementRule")
        .returns(CallStatement)
        .as(({ set, add }) => [
            "call",
            set("callee", ref(CallableTransformation, ID)),
            "(",
            ...manySep(add("arguments", CallArgumentRule), ",", LeadingTrailing.TRAILING),
            ")",
            optional(group("->", "(", ...manySep(add("outputs", CallOutputRule), ",", LeadingTrailing.TRAILING), ")"))
        ]);

    /**
     * If-call condition rule (inner node for scoping: the outputs are visible in the then block).
     * Format: call name(...) [-> (...)] then { block }
     */
    const IfCallConditionRule = createRule("IfCallConditionRule")
        .returns(IfCallConditionAndBlock)
        .as(({ set }) => [set("call", CallStatementRule), "then", set("thenBlock", () => StatementsScopeRule)]);

    /**
     * If-call statement rule.
     * Format: if call name(...) [-> (...)] then { block } [else { block }]
     */
    const IfCallStatementRule = createRule("IfCallStatementRule")
        .returns(IfCallStatement)
        .as(({ set }) => [
            "if",
            set("ifBlock", IfCallConditionRule),
            optional(
                group(
                    "else",
                    set("elseBlock", () => StatementsScopeRule)
                )
            )
        ]);

    /**
     * Loop variable rule of a for-in statement.
     */
    const ForInVariableRule = createRule("ForInVariableRule")
        .returns(ForInVariable)
        .as(({ set }) => [set("name", ID)]);

    /**
     * For-in statement rule.
     * Format: for name in expression do { block }
     */
    const ForInStatementRule = createRule("ForInStatementRule")
        .returns(ForInStatement)
        .as(({ set }) => [
            "for",
            set("variable", ForInVariableRule),
            "in",
            set("collection", ExpressionRule),
            "do",
            set("doBlock", () => StatementsScopeRule)
        ]);

    /**
     * Assignment statement rule.
     * Format: name = expression | name += expression
     */
    const AssignmentStatementRule = createRule("AssignmentStatementRule")
        .returns(AssignmentStatement)
        .as(({ set }) => [
            set("variable", ref(AssignableVariable, ID)),
            or(set("operator", "+="), set("operator", "=")),
            set("value", ExpressionRule)
        ]);

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
                StopStatementRule,
                CallStatementRule,
                IfCallStatementRule,
                ForInStatementRule,
                AssignmentStatementRule
            )
        ]);

    /**
     * Header declaration of an input or output.
     * Format: in name: Type [= default] | out name: Type
     *
     * The declarations are only part of the header (after `using` and the imports, before the
     * first statement); the root rule places them, so a declaration after a statement does not
     * parse. A default on an `out` parameter is rejected by the validator.
     */
    const TransformationParameterRule = createRule("TransformationParameterRule")
        .returns(TransformationParameter)
        .as(({ set }) => [
            or(set("direction", "in"), set("direction", "out")),
            set("name", ID),
            ":",
            set("type", TypeRule),
            optional(group("=", set("defaultValue", ExpressionRule)))
        ]);

    /**
     * Path import of another transformation.
     * Format: import "./file.mt" as alias
     *
     * Every `.mt` file is one transformation; the alias is its only name in this file.
     */
    const TransformationFileImportRule = createRule("TransformationFileImportRule")
        .returns(TransformationFileImport)
        .as(({ set }) => ["import", set("file", STRING), "as", set("name", ID)]);

    /**
     * Metamodel file import rule.
     * Format: using "filename"
     */
    const MetamodelFileImportRule = createRule("MetamodelFileImportRule")
        .returns(MetamodelFileImport)
        .as(({ set }) => ["using", set("file", STRING)]);

    /**
     * Root rule for Model Transformation language.
     * Format: using "metamodel", then the path imports of other transformations, then the
     * header declarations (`in …` / `out …`, one per line), then the statements.
     */
    const ModelTransformationRule = createRule("ModelTransformationRule")
        .returns(ModelTransformation)
        .as(({ add, set }) => [
            many(NEWLINE),
            set("import", MetamodelFileImportRule),
            many(or(add("imports", TransformationFileImportRule), NEWLINE)),
            many(or(add("parameters", TransformationParameterRule), NEWLINE)),
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
