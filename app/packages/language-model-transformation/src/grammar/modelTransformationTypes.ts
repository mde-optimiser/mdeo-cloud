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
 * Modifier for pattern elements (create, delete).
 *
 * Negative and positive application conditions are no longer expressed as element
 * modifiers; they are expressed as {@link PatternApplicationCondition} blocks, which
 * carry their own (separate) graph.
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
 * value of an already-declared one: a `var` of an enclosing scope or an `out` parameter of the
 * transformation. Like declarations, the reassignment is performed as
 * part of the match, before any access in that match block.
 */
export const PatternVariableReassignment = createInterface("PatternVariableReassignment").attrs({
    variable: Ref(() => AssignableVariable),
    value: BaseExpression
});

/**
 * Type representing a PatternVariableReassignment AST node.
 */
export type PatternVariableReassignmentType = ASTType<typeof PatternVariableReassignment>;

/**
 * Addition of an element to a collection `out` parameter.
 * Format: name += expression
 *
 * The value is computed with the match, after its modifications, so it can be an object the
 * pattern creates. In a `for match` every match adds its value.
 */
export const PatternCollectionAppend = createInterface("PatternCollectionAppend").attrs({
    variable: Ref(() => AssignableVariable),
    value: BaseExpression
});

/**
 * Type representing a PatternCollectionAppend AST node.
 */
export type PatternCollectionAppendType = ASTType<typeof PatternCollectionAppend>;

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
 * Optionally prefixed with create or delete.
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
    instance: Ref(() => ObjectInstanceDeclaration),
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
    instance: Ref(() => ObjectInstanceDeclaration)
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
    object: Ref(() => ObjectInstanceDeclaration),
    property: Optional(Ref(() => Property))
});

/**
 * Type representing a PatternLinkEnd AST node.
 */
export type PatternLinkEndType = ASTType<typeof PatternLinkEnd>;

/**
 * Link definition in a pattern.
 * Optionally prefixed with create or delete.
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
 * Base of the blocks inside a pattern: application conditions (`forbid { … }`,
 * `require { … }`) and model scope blocks (`in donor { … }`). The two may contain each other,
 * so the element unions of both name this base instead of the other block type.
 */
export const PatternBlock = createInterface("PatternBlock").attrs({});

/**
 * Type representing a PatternBlock AST node.
 */
export type PatternBlockType = ASTType<typeof PatternBlock>;

/**
 * Element type union for the body of an application-condition block.
 *
 * A condition block describes its own, separate graph, so it may only contain the
 * structural elements of that graph: object instances (and references to instances of
 * the enclosing pattern), links, where clauses, and model scope blocks
 * ({@link PatternModelScope}) that put part of the condition graph into an input model.
 */
export const ApplicationConditionElement: BaseType<AstNode> = createType("ApplicationConditionElement").types(
    PatternObjectInstance,
    PatternObjectInstanceReference,
    PatternLink,
    WhereClause,
    PatternBlock
);

/**
 * Type representing an ApplicationConditionElement AST node.
 */
export type ApplicationConditionElementType = ASTType<typeof ApplicationConditionElement>;

/**
 * A negative (`forbid`) or positive (`require`) application condition.
 *
 * Format: `forbid [name] { ... }` / `require [name] { ... }`
 *
 * Each block is a graph of its own that is matched independently of every other block:
 * a `forbid` block rejects the match as soon as *its whole* sub-pattern can be found,
 * a `require` block demands that its whole sub-pattern can be found. Two separate
 * `forbid` blocks therefore reject the match if *either* of them matches, while the
 * elements inside one block only reject it when they *all* match together — the
 * distinction that element-level `forbid` modifiers could not express.
 *
 * The optional name follows Henshin's nested-name style; it identifies the block in the
 * graphical syntax (elements are tagged with `«forbid name»`) and in diagnostics. A name
 * is only worth writing when it says something, so the editor leaves the blocks it
 * creates unnamed and shows them numbered instead.
 */
export const PatternApplicationCondition = createInterface("PatternApplicationCondition")
    .extends(PatternBlock)
    .attrs({
        kind: String,
        name: Optional(String),
        elements: [ApplicationConditionElement]
    });

/**
 * Type representing a PatternApplicationCondition AST node.
 */
export type PatternApplicationConditionType = ASTType<typeof PatternApplicationCondition>;

/**
 * Element type union for the body of a model scope block: everything that finds or reads
 * objects of the scoped model. Nothing that changes a model is part of it; `create`/`delete`
 * modifiers, `delete name` and property assignments (`=`) are parsed for better messages and
 * rejected by the validator, since input models are read-only.
 */
export const ModelScopeElement: BaseType<AstNode> = createType("ModelScopeElement").types(
    PatternVariable,
    PatternObjectInstance,
    PatternObjectInstanceReference,
    PatternObjectInstanceDelete,
    PatternLink,
    WhereClause,
    PatternBlock
);

/**
 * Type representing a ModelScopeElement AST node.
 */
export type ModelScopeElementType = ASTType<typeof ModelScopeElement>;

/**
 * A model scope block of a pattern: the objects declared in it are objects of an input model
 * instead of the model the transformation runs on (its implicit model).
 *
 * Format: `in model { ... }`, where `model` is a `model` input of the transformation or the
 * variable of a `for … in` loop over a `List<model>` input.
 *
 * The block is not a scope of names: its objects and variables are visible to the rest of the
 * pattern (and after it) exactly as if they were declared outside of it, so the implicit model
 * can read them in expressions. Only the matching is scoped: the block's objects match objects
 * of the scoped model only, and its links connect them only. A `forbid` / `require` block inside
 * a scope block, or a scope block inside one, describes a condition graph in that model.
 */
export const PatternModelScope = createInterface("PatternModelScope")
    .extends(PatternBlock)
    .attrs({
        model: Ref(() => ModelValueDeclaration),
        elements: [ModelScopeElement]
    });

/**
 * Type representing a PatternModelScope AST node.
 */
export type PatternModelScopeType = ASTType<typeof PatternModelScope>;

/**
 * Base pattern element type union.
 */
export const PatternElement: BaseType<AstNode> = createType("PatternElement").types(
    PatternVariable,
    PatternVariableReassignment,
    PatternCollectionAppend,
    PatternObjectInstance,
    PatternObjectInstanceDelete,
    PatternLink,
    WhereClause,
    PatternApplicationCondition,
    PatternModelScope
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
 * An input or output of a transformation, declared in the header of the file (after `using`
 * and the imports, before the first statement), one per line.
 * Format: `in name: Type [= default]` or `out name: Type`
 *
 * An `in` parameter is bound before the transformation's first statement: a model element
 * (a metamodel class type) is a bound object that patterns reference as `name { }`, a scalar
 * (`int`, `long`, `float`, `double`, `string`, `boolean` or an enum) is a value for
 * expressions, a `model` (or `List<model>`) is another model of the transformation's metamodel
 * that scope blocks (`in name { … }`) read. An `out` parameter starts as `null` and is assigned
 * with `name = expression` inside a pattern; the caller binds it after a successful call.
 */
export const TransformationParameter = createInterface("TransformationParameter").attrs({
    direction: String,
    name: String,
    type: typeTypes.baseTypeType,
    defaultValue: Optional(BaseExpression)
});

/**
 * Type representing a TransformationParameter AST node.
 */
export type TransformationParameterType = ASTType<typeof TransformationParameter>;

/**
 * Base of the transformation root ({@link ModelTransformation}), the target of a call's callee
 * reference. The callee is typed with this base instead of the root itself, which would make the
 * root's type refer to itself (through its statements); use {@link getCallee} to get the root.
 */
export const CallableTransformation = createInterface("CallableTransformation").attrs({});

/**
 * Type representing a CallableTransformation AST node.
 */
export type CallableTransformationType = ASTType<typeof CallableTransformation>;

/**
 * A named argument of a call.
 * Format: `parameter = expression`
 */
export const CallArgument = createInterface("CallArgument").attrs({
    parameter: Ref(() => TransformationParameter),
    value: BaseExpression
});

/**
 * Type representing a CallArgument AST node.
 */
export type CallArgumentType = ASTType<typeof CallArgument>;

/**
 * The binding of a callee's `out` parameter to a new name of the caller.
 * Format: `parameter: name`
 *
 * The name is bound after the call as if a match had bound it: an element output is an
 * object that later patterns reference as `name { }`, a scalar output is a value.
 */
export const CallOutput = createInterface("CallOutput").attrs({
    parameter: Ref(() => TransformationParameter),
    name: String
});

/**
 * Type representing a CallOutput AST node.
 */
export type CallOutputType = ASTType<typeof CallOutput>;

/**
 * Call of another transformation.
 * Format: `call alias(argument, ...) [-> (output, ...)]`
 *
 * The callee is the transformation a path import (`import "./file.mt" as alias`) names; the
 * reference resolves to the root of the imported file. The callee runs on the same model with
 * its inputs bound. A callee that fails (a `match`
 * that does not match, or `kill`) fails the call, and so the calling transformation; `stop` in
 * the callee only returns from the callee.
 */
export const CallStatement = createInterface("CallStatement")
    .extends(BaseTransformationStatement)
    .attrs({
        callee: Ref(() => CallableTransformation),
        arguments: [CallArgument],
        outputs: [CallOutput]
    });

/**
 * Type representing a CallStatement AST node.
 */
export type CallStatementType = ASTType<typeof CallStatement>;

/**
 * The call of an `if call` together with its then block, so that the call's outputs are
 * visible in the then block and nowhere else.
 * Format: `call name(...) [-> (...)] then { block }`
 */
export const IfCallConditionAndBlock = createInterface("IfCallConditionAndBlock").attrs({
    call: CallStatement,
    thenBlock: StatementsScope
});

/**
 * Type representing an IfCallConditionAndBlock AST node.
 */
export type IfCallConditionAndBlockType = ASTType<typeof IfCallConditionAndBlock>;

/**
 * Conditional call of another transformation.
 * Format: `if call name(...) [-> (...)] then { block } [else { block }]`
 *
 * When the callee succeeds its outputs are bound and the then block runs; when it fails the
 * else block runs. `kill` in the callee still ends everything. The validator only accepts a
 * callee that fails before it changes the model, since the engine cannot undo changes.
 */
export const IfCallStatement = createInterface("IfCallStatement")
    .extends(BaseTransformationStatement)
    .attrs({
        ifBlock: IfCallConditionAndBlock,
        elseBlock: Optional(StatementsScope)
    });

/**
 * Type representing an IfCallStatement AST node.
 */
export type IfCallStatementType = ASTType<typeof IfCallStatement>;

/**
 * The loop variable of a `for x in collection` statement.
 */
export const ForInVariable = createInterface("ForInVariable").attrs({
    name: String
});

/**
 * Type representing a ForInVariable AST node.
 */
export type ForInVariableType = ASTType<typeof ForInVariable>;

/**
 * Loop over the elements of a collection.
 * Format: `for name in expression do { block }`
 *
 * The block runs once per element, with the loop variable bound to it: an element of a
 * collection of model elements is a bound object (`name { }` in the block's patterns), an
 * element of a collection of scalars is a value.
 */
export const ForInStatement = createInterface("ForInStatement").extends(BaseTransformationStatement).attrs({
    variable: ForInVariable,
    collection: BaseExpression,
    doBlock: StatementsScope
});

/**
 * Type representing a ForInStatement AST node.
 */
export type ForInStatementType = ASTType<typeof ForInStatement>;

/**
 * Assignment outside a pattern.
 * Format: `name = expression` or `name += expression`
 *
 * `=` assigns a variable of an enclosing scope or an `out` parameter, `+=` adds to a collection
 * `out` parameter.
 */
export const AssignmentStatement = createInterface("AssignmentStatement")
    .extends(BaseTransformationStatement)
    .attrs({
        variable: Ref(() => AssignableVariable),
        operator: String,
        value: BaseExpression
    });

/**
 * Type representing an AssignmentStatement AST node.
 */
export type AssignmentStatementType = ASTType<typeof AssignmentStatement>;

/**
 * Everything a pattern can reference as an object (`name { }`, `delete name`, link ends):
 * object instances of patterns, element parameters of the transformation, element outputs of
 * calls and the variables of `for … in` loops.
 */
export const ObjectInstanceDeclaration = createType("ObjectInstanceDeclaration").types(
    PatternObjectInstance,
    TransformationParameter,
    CallOutput,
    ForInVariable
);

/**
 * Type representing an ObjectInstanceDeclaration AST node.
 */
export type ObjectInstanceDeclarationType = ASTType<typeof ObjectInstanceDeclaration>;

/**
 * Everything that can denote a model value (the target of a scope block `in name { … }`): a
 * `model` input of the transformation and the variable of a `for … in` loop over a
 * `List<model>` input. Which of them really is a model value is checked by the validator.
 */
export const ModelValueDeclaration = createType("ModelValueDeclaration").types(TransformationParameter, ForInVariable);

/**
 * Type representing a ModelValueDeclaration AST node.
 */
export type ModelValueDeclarationType = ASTType<typeof ModelValueDeclaration>;

/**
 * Everything a reassignment (`name = expression`) can assign: pattern variables and the
 * `out` parameters of the transformation.
 */
export const AssignableVariable = createType("AssignableVariable").types(PatternVariable, TransformationParameter);

/**
 * Type representing an AssignableVariable AST node.
 */
export type AssignableVariableType = ASTType<typeof AssignableVariable>;

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
    StopStatement,
    CallStatement,
    IfCallStatement,
    ForInStatement,
    AssignmentStatement
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
 * A path import of another transformation: every `.mt` file is one transformation, which this
 * file calls by the alias.
 * Format: `import "./file.mt" as alias`
 *
 * The alias is the only name of the imported transformation in this file; a call
 * (`call alias(…)`) resolves to the root of the imported file.
 */
export const TransformationFileImport = createInterface("TransformationFileImport").attrs({
    file: String,
    name: String
});

/**
 * Type representing a TransformationFileImport AST node.
 */
export type TransformationFileImportType = ASTType<typeof TransformationFileImport>;

/**
 * Model Transformation root interface.
 * Contains the metamodel import, the path imports of other transformations, the header
 * declarations of the inputs and outputs (`in …` / `out …` lines) and the transformation
 * statements.
 */
export const ModelTransformation = createInterface("ModelTransformation")
    .extends(CallableTransformation)
    .attrs({
        import: MetamodelFileImport,
        imports: [TransformationFileImport],
        parameters: [TransformationParameter],
        statements: [TransformationStatement]
    });

/**
 * Model Transformation AST type.
 */
export type ModelTransformationType = ASTType<typeof ModelTransformation>;

/**
 * Returns the transformation a call calls: the root of the imported file its alias names.
 *
 * @param call The call statement
 * @returns The callee's root, or undefined when the alias does not resolve
 */
export function getCallee(call: CallStatementType | undefined): ModelTransformationType | undefined {
    return call?.callee?.ref as ModelTransformationType | undefined;
}
