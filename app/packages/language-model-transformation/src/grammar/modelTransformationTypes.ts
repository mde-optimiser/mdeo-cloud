import { createInterface, createType, Optional, Ref, type ASTType, type BaseType } from "@mdeo/language-common";
import { ExpressionConfig, generateExpressionTypes, TypeConfig, generateTypeTypes } from "@mdeo/language-expression";
import { Class, Property } from "@mdeo/language-metamodel";
import type { AstNode } from "langium";

/**
 * Configuration for Model Transformation type annotations.
 */
export const typeConfig = new TypeConfig("ModelTransformation");

/**
 * Generated type types for Model Transformation.
 */
export const typeTypes = generateTypeTypes(typeConfig);

/**
 * Configuration for Model Transformation expressions.
 */
export const expressionConfig = new ExpressionConfig("ModelTransformation");

/**
 * Generated expression types for Model Transformation.
 */
export const expressionTypes = generateExpressionTypes(expressionConfig, typeTypes);

/**
 * Base expression type alias for convenience.
 */
export const BaseExpression = expressionTypes.baseExpressionType;

/**
 * Modifier for pattern elements (create, delete, forbid, require).
 */
export const PatternModifier = createInterface("PatternModifier").attrs({
    modifier: String
});

/**
 * Type representing a PatternModifier AST node.
 */
export type PatternModifierType = ASTType<typeof PatternModifier>;

/**
 * Variable declaration in a pattern.
 * Format: var name[: type] = expression
 */
export const PatternVariable = createInterface("PatternVariable").attrs({
    name: String,
    type: Optional(typeTypes.baseTypeType),
    value: BaseExpression
});

/**
 * Type representing a PatternVariable AST node.
 */
export type PatternVariableType = ASTType<typeof PatternVariable>;

/**
 * Reassignment of a variable declared in an enclosing scope.
 * Format: name = expression
 *
 * Unlike {@link PatternVariable}, this does not declare a new variable; it updates the
 * value of an already-declared one. Like declarations, the reassignment is performed as
 * part of the match, before any access in that match block.
 */
export const PatternVariableReassignment = createInterface("PatternVariableReassignment").attrs({
    variable: Ref(() => PatternVariable),
    value: BaseExpression
});

/**
 * Type representing a PatternVariableReassignment AST node.
 */
export type PatternVariableReassignmentType = ASTType<typeof PatternVariableReassignment>;

/**
 * Property assignment in a pattern object instance.
 * Uses = for assignment or == for comparison.
 */
export const PatternPropertyAssignment = createInterface("PatternPropertyAssignment").attrs({
    name: Ref(() => Property),
    operator: String,
    value: BaseExpression
});

/**
 * Type representing a PatternPropertyAssignment AST node.
 */
export type PatternPropertyAssignmentType = ASTType<typeof PatternPropertyAssignment>;

/**
 * Pattern object instance definition.
 * Optionally prefixed with create, delete, forbid, or require.
 */
export const PatternObjectInstance = createInterface("PatternObjectInstance").attrs({
    modifier: Optional(PatternModifier),
    name: String,
    class: Ref(() => Class),
    properties: [PatternPropertyAssignment]
});

/**
 * Type representing a PatternObjectInstance AST node.
 */
export type PatternObjectInstanceType = ASTType<typeof PatternObjectInstance>;

/**
 * Reference to a pattern object instance, used for referencing previously matched nodes.
 * Contains the instance reference and optional property assignments for constraints.
 */
export const PatternObjectInstanceReference = createInterface("PatternObjectInstanceReference").attrs({
    instance: Ref(PatternObjectInstance),
    properties: [PatternPropertyAssignment]
});

/**
 * Type representing a PatternObjectInstanceReference AST node.
 */
export type PatternObjectInstanceReferenceType = ASTType<typeof PatternObjectInstanceReference>;

/**
 * Pattern object delete.
 * Deletes an already named node from a previous match.
 * Format: delete nameOfTheNode
 */
export const PatternObjectInstanceDelete = createInterface("PatternObjectDelete").attrs({
    instance: Ref(() => PatternObjectInstance)
});

/**
 * Type representing a PatternObjectDelete AST node.
 */
export type PatternObjectInstanceDeleteType = ASTType<typeof PatternObjectInstanceDelete>;

/**
 * Link end in a pattern.
 * References an object instance with optional property.
 */
export const PatternLinkEnd = createInterface("PatternLinkEnd").attrs({
    object: Ref(() => PatternObjectInstance),
    property: Optional(Ref(() => Property))
});

/**
 * Type representing a PatternLinkEnd AST node.
 */
export type PatternLinkEndType = ASTType<typeof PatternLinkEnd>;

/**
 * Link definition in a pattern.
 * Optionally prefixed with create, delete, forbid, or require.
 */
export const PatternLink = createInterface("PatternLink").attrs({
    modifier: Optional(PatternModifier),
    source: PatternLinkEnd,
    target: PatternLinkEnd
});

/**
 * Type representing a PatternLink AST node.
 */
export type PatternLinkType = ASTType<typeof PatternLink>;

/**
 * Where clause in a pattern for additional constraints.
 */
export const WhereClause = createInterface("WhereClause").attrs({
    expression: BaseExpression
});

/**
 * Type representing a WhereClause AST node.
 */
export type WhereClauseType = ASTType<typeof WhereClause>;

/**
 * Base pattern element type union.
 */
export const PatternElement: BaseType<AstNode> = createType("PatternElement").types(
    PatternVariable,
    PatternVariableReassignment,
    PatternObjectInstance,
    PatternObjectInstanceDelete,
    PatternLink,
    WhereClause
);

/**
 * Type representing a PatternElement AST node.
 */
export type PatternElementType = ASTType<typeof PatternElement>;

/**
 * Pattern block containing pattern elements.
 */
export const Pattern = createInterface("Pattern").attrs({
    elements: [PatternElement]
});

/**
 * Type representing a Pattern AST node.
 */
export type PatternType = ASTType<typeof Pattern>;

/**
 * Base transformation statement type.
 * All transformation statements extend this.
 */
export const BaseTransformationStatement = createInterface("BaseTransformationStatement").attrs({});

/**
 * Type representing a BaseTransformationStatement AST node.
 */
export type BaseTransformationStatementType = ASTType<typeof BaseTransformationStatement>;

/**
 * StatementsScope containing transformation statements.
 */
export const StatementsScope = createInterface("StatementsScope").attrs({
    statements: [BaseTransformationStatement]
});

/**
 * Type representing a StatementsScope AST node.
 */
export type StatementsScopeType = ASTType<typeof StatementsScope>;

/**
 * Match statement for pattern matching.
 * Format: match { pattern }
 */
export const MatchStatement = createInterface("MatchStatement").extends(BaseTransformationStatement).attrs({
    pattern: Pattern
});

/**
 * Type representing a MatchStatement AST node.
 */
export type MatchStatementType = ASTType<typeof MatchStatement>;

/**
 * If-match condition scope containing the pattern and then block.
 * This ensures the then block has the pattern in its parent scope.
 * Format: match { pattern } then { block }
 */
export const IfMatchConditionAndBlock = createInterface("IfMatchConditionAndBlock").attrs({
    pattern: Pattern,
    thenBlock: StatementsScope
});

/**
 * Type representing an IfMatchCondition AST node.
 */
export type IfMatchConditionAndBlockType = ASTType<typeof IfMatchConditionAndBlock>;

/**
 * If-match statement with conditional pattern matching.
 * Format: if match { pattern } then { block } [else { block }]
 */
export const IfMatchStatement = createInterface("IfMatchStatement")
    .extends(BaseTransformationStatement)
    .attrs({
        ifBlock: IfMatchConditionAndBlock,
        elseBlock: Optional(StatementsScope)
    });

/**
 * Type representing an IfMatchStatement AST node.
 */
export type IfMatchStatementType = ASTType<typeof IfMatchStatement>;

/**
 * While-match statement with looping pattern matching.
 * Format: while match { pattern } do { block }
 */
export const WhileMatchStatement = createInterface("WhileMatchStatement").extends(BaseTransformationStatement).attrs({
    pattern: Pattern,
    doBlock: StatementsScope
});

/**
 * Type representing a WhileMatchStatement AST node.
 */
export type WhileMatchStatementType = ASTType<typeof WhileMatchStatement>;

/**
 * Until-match statement with looping until pattern matches.
 * Format: until match { pattern } do { block }
 */
export const UntilMatchStatement = createInterface("UntilMatchStatement").extends(BaseTransformationStatement).attrs({
    pattern: Pattern,
    doBlock: StatementsScope
});

/**
 * Type representing an UntilMatchStatement AST node.
 */
export type UntilMatchStatementType = ASTType<typeof UntilMatchStatement>;

/**
 * For-match statement for iterating over all matches.
 * Format: for match { pattern } do { block }
 */
export const ForMatchStatement = createInterface("ForMatchStatement").extends(BaseTransformationStatement).attrs({
    pattern: Pattern,
    doBlock: StatementsScope
});

/**
 * Type representing a ForMatchStatement AST node.
 */
export type ForMatchStatementType = ASTType<typeof ForMatchStatement>;

/**
 * Else-if branch in an if-expression statement.
 */
export const ElseIfBranch = createInterface("ElseIfBranch").attrs({
    condition: BaseExpression,
    block: StatementsScope
});

/**
 * Type representing an ElseIfBranch AST node.
 */
export type ElseIfBranchType = ASTType<typeof ElseIfBranch>;

/**
 * If-expression statement with expression-based conditions.
 * Format: if (expression) { block } [[else if (expression) { block }]* else { block }]
 */
export const IfExpressionStatement = createInterface("IfExpressionStatement")
    .extends(BaseTransformationStatement)
    .attrs({
        condition: BaseExpression,
        thenBlock: StatementsScope,
        elseIfBranches: [ElseIfBranch],
        elseBlock: Optional(StatementsScope)
    });

/**
 * Type representing an IfExpressionStatement AST node.
 */
export type IfExpressionStatementType = ASTType<typeof IfExpressionStatement>;

/**
 * While-expression statement with expression-based looping.
 * Format: while (expression) { block }
 */
export const WhileExpressionStatement = createInterface("WhileExpressionStatement")
    .extends(BaseTransformationStatement)
    .attrs({
        condition: BaseExpression,
        block: StatementsScope
    });

/**
 * Type representing a WhileExpressionStatement AST node.
 */
export type WhileExpressionStatementType = ASTType<typeof WhileExpressionStatement>;

/**
 * Kill/stop statement to terminate transformation.
 * Format: kill or stop
 */
export const StopStatement = createInterface("StopStatement").extends(BaseTransformationStatement).attrs({
    keyword: String
});

/**
 * Type representing a StopStatement AST node.
 */
export type StopStatementType = ASTType<typeof StopStatement>;

/**
 * Union of all transformation statement types.
 */
export const TransformationStatement: BaseType<AstNode> = createType("TransformationStatement").types(
    MatchStatement,
    IfMatchStatement,
    WhileMatchStatement,
    UntilMatchStatement,
    ForMatchStatement,
    IfExpressionStatement,
    WhileExpressionStatement,
    StopStatement
);

/**
 * Type representing a TransformationStatement AST node.
 */
export type TransformationStatementType = ASTType<typeof TransformationStatement>;

/**
 * Lambda parameter for lambda expressions.
 */
export const LambdaParameter = createInterface("LambdaParameter").attrs({
    name: String
});

/**
 * Lambda parameters list with parentheses.
 */
export const LambdaParameters = createInterface("LambdaParameters").attrs({
    parameters: [LambdaParameter]
});

/**
 * Lambda expression with expression body only.
 * Format: (params) => expression
 */
export const LambdaExpression = createInterface("LambdaExpression").extends(BaseExpression).attrs({
    parameterList: LambdaParameters,
    expression: BaseExpression
});

/**
 * Type representing a LambdaExpression AST node.
 */
export type LambdaExpressionType = ASTType<typeof LambdaExpression>;

/**
 * Type representing a LambdaParameter AST node.
 */
export type LambdaParameterType = ASTType<typeof LambdaParameter>;

/**
 * Type representing a LambdaParameters AST node.
 */
export type LambdaParametersType = ASTType<typeof LambdaParameters>;

/**
 * Metamodel file import for the transformation.
 */
export const MetamodelFileImport = createInterface("MetamodelFileImport").attrs({
    file: String
});

/**
 * Type representing a MetamodelFileImport AST node.
 */
export type MetamodelFileImportType = ASTType<typeof MetamodelFileImport>;

/**
 * Model Transformation root interface.
 * Contains metamodel import and transformation statements.
 */
export const ModelTransformation = createInterface("ModelTransformation").attrs({
    import: MetamodelFileImport,
    statements: [TransformationStatement]
});

/**
 * Model Transformation AST type.
 */
export type ModelTransformationType = ASTType<typeof ModelTransformation>;
