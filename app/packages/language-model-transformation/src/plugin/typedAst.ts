import type { ReturnType, TypedExpression } from "@mdeo/language-expression";

/**
 * Root of the TypedAST containing all program information.
 */
export interface TypedAst {
    /**
     * Array of all types used in the program.
     * Generics are replaced by Any? due to type erasure.
     * Indexed by typeIndex in expressions.
     */
    types: ReturnType[];

    /**
     * Absolute path of the imported metamodel file.
     */
    metamodelPath: string;

    /**
     * All top-level transformation statements.
     */

    statements: TypedTransformationStatement[];

    /**
     * The header declarations (`in …` / `out …`) of the transformation. Always present; a file
     * without declarations has an empty parameter list.
     */
    signature?: TypedTransformationSignature;

    /**
     * The typed ASTs of every transformation this one calls, directly or through its callees,
     * by absolute file path. Only the root of a bundle has callees; the bundled ASTs have none
     * of their own, so the engine finds every callee in the root's map.
     */
    callees?: Record<string, TypedAst>;
}

/**
 * The inputs and outputs of a transformation (its header declarations). Every transformation
 * file can be imported and called.
 */
export interface TypedTransformationSignature {
    /**
     * The display name of the transformation: the base name of its file including the
     * extension, e.g. `move-component.mt` (for diagnostics and `failedAt`).
     */
    name: string;

    /**
     * The parameters, in declaration order.
     */
    parameters: TypedTransformationParameter[];
}

/**
 * A header declaration (`in name: Type [= default]` / `out name: Type`).
 */
export interface TypedTransformationParameter {
    /**
     * The parameter's name; the engine binds it at the transformation's own scope level (1).
     */
    name: string;

    /**
     * `in` or `out`.
     */
    direction: "in" | "out";

    /**
     * Index into the types array for the parameter's type.
     */
    type: number;

    /**
     * Whether the parameter is a model element (a metamodel class) rather than a scalar or a
     * collection.
     */
    isElement: boolean;

    /**
     * The default value of an optional input, evaluated in the callee before its first statement.
     */
    defaultValue?: TypedExpression;

    /**
     * For a collection parameter: `List` or `Set`. A collection output starts empty.
     */
    collection?: "List" | "Set";

    /**
     * For a collection parameter: whether its elements are model elements.
     */
    elementsAreElements?: boolean;

    /**
     * Whether the input may be left out: its type is nullable and it has no default. It is then
     * null, or, when it pins an object (see {@link pinsNode}), unbound.
     */
    optional?: boolean;

    /**
     * Whether the input pins the object of the same name in the first pattern: when it is
     * passed (and not null), the object is bound to it; otherwise the input is left unbound and
     * the object is matched freely.
     */
    pinsNode?: boolean;

    /**
     * Whether the input is a model input: `in x: model` (no {@link collection}) or
     * `in xs: List<model>` ({@link collection} `List`). The type index points to the builtin
     * `model` type (or `List<model>`). A model input is always an `in` parameter without a default,
     * neither an element nor optional; the engine passes models by reference (never copies).
     * Absent (false) for every other parameter.
     */
    isModel?: boolean;
}

/**
 * A named argument of a call.
 */
export interface TypedTransformationCallArgument {
    /**
     * The callee parameter the argument is passed to.
     */
    name: string;

    /**
     * The value, evaluated in the caller before the call.
     */
    value: TypedExpression;
}

/**
 * The binding of a callee output to a name of the caller.
 */
export interface TypedTransformationCallOutput {
    /**
     * The callee's `out` parameter.
     */
    parameter: string;

    /**
     * The caller's name the output is bound to, in the scope of the call (like the objects of a
     * match).
     */
    name: string;
}

/**
 * Call of another transformation.
 */
export interface TypedCallStatement extends TypedTransformationStatement {
    kind: "call";

    /**
     * Absolute path of the callee's file, the key of its typed AST in the root's callees.
     */
    callee: string;

    /**
     * The alias the caller imports the callee under (`import "./f.mt" as alias`), for messages.
     */
    name: string;

    /**
     * The arguments passed.
     */
    arguments: TypedTransformationCallArgument[];

    /**
     * The outputs bound.
     */
    outputs: TypedTransformationCallOutput[];
}

/**
 * Conditional call of another transformation: the then block runs, with the outputs bound,
 * when the callee succeeds; the else block when it fails.
 */
export interface TypedIfCallStatement extends TypedTransformationStatement {
    kind: "ifCall";

    /**
     * The call; its outputs are bound in the scope of the then block's parent (one level below
     * the statement).
     */
    call: TypedCallStatement;

    /**
     * Statements run after a successful call.
     */
    thenBlock: TypedTransformationStatement[];

    /**
     * Statements run after a failed call.
     */
    elseBlock?: TypedTransformationStatement[];
}

/**
 * Loop over the elements of a collection.
 */
export interface TypedForInStatement extends TypedTransformationStatement {
    kind: "forIn";

    /**
     * The loop variable, bound one level below the statement to each element in turn.
     */
    variable: string;

    /**
     * The collection, evaluated once before the first iteration.
     */
    collection: TypedExpression;

    /**
     * Statements run per element.
     */
    doBlock: TypedTransformationStatement[];
}

/**
 * Assignment outside a pattern: `name = value` assigns a variable or an output, `name += value`
 * adds to a collection output.
 */
export interface TypedAssignmentStatement extends TypedTransformationStatement {
    kind: "assignment";

    /**
     * The assigned variable or output.
     */
    name: string;

    /**
     * `=` or `+=`.
     */
    operator: "=" | "+=";

    /**
     * The value.
     */
    value: TypedExpression;
}

/**
 * Base interface for all transformation statements.
 */
export interface TypedTransformationStatement {
    /**
     * The kind of statement.
     */
    kind: string;
}

/**
 * Pattern variable declaration.
 */
export interface TypedPatternVariable {
    /**
     * Name of the variable.
     */
    name: string;

    /**
     * Optional index into the types array for the variable type.
     * If undefined, the type is inferred from the value expression.
     */
    type?: number;

    /**
     * The value expression assigned to the variable.
     */
    value: TypedExpression;
}

/**
 * Reassignment of a variable declared in an enclosing scope.
 */
export interface TypedPatternVariableReassignment {
    /**
     * Name of the variable being reassigned.
     */
    name: string;

    /**
     * The value expression assigned to the variable.
     */
    value: TypedExpression;
}

/**
 * Discriminated union of all operators that can appear in a
 * PatternPropertyAssignment.
 *
 * - `"="` — assignment (sets the property when creating/modifying an instance).
 * - `"=="` — equality comparison constraint.
 * - `"!="` — inequality comparison constraint.
 * - `"<"`, `">"`, `"<="`, `">="` — relational comparison constraints
 *   (only valid for comparable / numeric property types).
 */
export type PatternPropertyOperator = "=" | "==" | "!=" | "<" | ">" | "<=" | ">=";

/**
 * Property assignment in a pattern object instance.
 */
export interface TypedPatternPropertyAssignment {
    /**
     * Name of the property being assigned.
     */
    propertyName: string;

    /**
     * The operator used.
     * `"="` is for assignment; all others are comparison constraints.
     */
    operator: PatternPropertyOperator;

    /**
     * The value expression.
     */
    value: TypedExpression;
}

/**
 * Pattern object instance definition.
 */
export interface TypedPatternObjectInstance {
    /**
     * Optional modifier (create or delete).
     */
    modifier?: string;

    /**
     * Name of the object instance.
     */
    name: string;

    /**
     * Fully qualified class name.
     * Optional to support referencing previously matched nodes.
     */
    className?: string;

    /**
     * Property assignments.
     */
    properties: TypedPatternPropertyAssignment[];

    /**
     * The model the object lives in: the name of the model value (a `model` input or the
     * variable of a `for … in` loop over a `List<model>` input) of the scope block
     * (`in donor { … }`) the object is declared or referenced in, also through `forbid` /
     * `require` blocks. Absent for objects of the implicit model (the model the transformation
     * runs on). Scope blocks themselves do not appear in the typed AST: their elements are
     * emitted in their place, and links get their model from their ends.
     */
    model?: string;
}

/**
 * Link end in a pattern.
 */
export interface TypedPatternLinkEnd {
    /**
     * Name of the object instance.
     */
    objectName: string;

    /**
     * Optional property name.
     */
    propertyName?: string;
}

/**
 * Pattern link definition.
 *
 * Represents a link (reference/association) between two object instances in a pattern.
 * The edge label can be computed from source and target property names.
 */
export interface TypedPatternLink {
    /**
     * Optional modifier (create or delete).
     */
    modifier?: string;

    /**
     * Source end of the link.
     */
    source: TypedPatternLinkEnd;

    /**
     * Target end of the link.
     */
    target: TypedPatternLinkEnd;
}

/**
 * Where clause in a pattern.
 */
export interface TypedWhereClause {
    /**
     * The condition expression.
     */
    expression: TypedExpression;
}

/**
 * Base interface for pattern elements.
 */
export interface TypedPatternElement {
    /**
     * The kind of pattern element.
     */
    kind: string;
}

/**
 * Pattern variable element.
 */
export interface TypedPatternVariableElement extends TypedPatternElement {
    kind: "variable";
    /**
     * The variable declaration.
     */
    variable: TypedPatternVariable;
}

/**
 * Pattern variable reassignment element.
 */
export interface TypedPatternVariableReassignmentElement extends TypedPatternElement {
    kind: "variableReassignment";
    /**
     * The variable reassignment.
     */
    reassignment: TypedPatternVariableReassignment;
}

/**
 * Addition of a value to a collection output, computed with the match after its
 * modifications.
 */
export interface TypedPatternCollectionAppend {
    /**
     * Name of the collection output.
     */
    name: string;

    /**
     * The value added (an element, or a collection whose elements are all added).
     */
    value: TypedExpression;
}

/**
 * Pattern collection append element.
 */
export interface TypedPatternCollectionAppendElement extends TypedPatternElement {
    kind: "collectionAppend";
    /**
     * The append.
     */
    append: TypedPatternCollectionAppend;
}

/**
 * Pattern object instance element.
 */
export interface TypedPatternObjectInstanceElement extends TypedPatternElement {
    kind: "objectInstance";
    /**
     * The object instance.
     */
    objectInstance: TypedPatternObjectInstance;
}

/**
 * Pattern link element.
 */
export interface TypedPatternLinkElement extends TypedPatternElement {
    kind: "link";
    /**
     * The link definition.
     */
    link: TypedPatternLink;
}

/**
 * Pattern where clause element.
 */
export interface TypedPatternWhereClauseElement extends TypedPatternElement {
    kind: "whereClause";
    /**
     * The where clause.
     */
    whereClause: TypedWhereClause;
}

/**
 * A negative (`forbid`) or positive (`require`) application condition.
 *
 * The condition holds a graph of its own. Its elements are matched together and
 * independently of every other condition: a negative condition rejects the match as soon
 * as its whole graph can be found, a positive one demands that its whole graph is found.
 */
export interface TypedPatternApplicationCondition {
    /**
     * Whether this is a negative application condition (`forbid`) rather than a positive
     * one (`require`).
     */
    negative: boolean;
    /**
     * Optional name identifying the condition graph in diagnostics and in the graphical
     * syntax.
     */
    name?: string;
    /**
     * The elements forming the condition graph. Object instances and links describe the
     * graph itself; where clauses constrain it and may read both the block's own nodes and
     * everything the enclosing match binds.
     */
    elements: (TypedPatternObjectInstanceElement | TypedPatternLinkElement | TypedPatternWhereClauseElement)[];
}

/**
 * Pattern application condition element.
 */
export interface TypedPatternApplicationConditionElement extends TypedPatternElement {
    kind: "applicationCondition";
    /**
     * The application condition.
     */
    condition: TypedPatternApplicationCondition;
}

/**
 * Pattern containing pattern elements.
 */
export interface TypedPattern {
    /**
     * Pattern elements.
     */
    elements: (
        | TypedPatternVariableElement
        | TypedPatternVariableReassignmentElement
        | TypedPatternCollectionAppendElement
        | TypedPatternObjectInstanceElement
        | TypedPatternLinkElement
        | TypedPatternWhereClauseElement
        | TypedPatternApplicationConditionElement
    )[];
}

/**
 * Match statement.
 */
export interface TypedMatchStatement extends TypedTransformationStatement {
    kind: "match";
    /**
     * The pattern to match.
     */
    pattern: TypedPattern;
}

/**
 * If-match statement.
 */
export interface TypedIfMatchStatement extends TypedTransformationStatement {
    kind: "ifMatch";
    /**
     * The pattern to match.
     */
    pattern: TypedPattern;
    /**
     * Statements to execute if pattern matches.
     */
    thenBlock: TypedTransformationStatement[];
    /**
     * Optional else block statements.
     */
    elseBlock?: TypedTransformationStatement[];
}

/**
 * While-match statement.
 */
export interface TypedWhileMatchStatement extends TypedTransformationStatement {
    kind: "whileMatch";
    /**
     * The pattern to match.
     */
    pattern: TypedPattern;
    /**
     * Statements to execute while pattern matches.
     */
    doBlock: TypedTransformationStatement[];
}

/**
 * Until-match statement.
 */
export interface TypedUntilMatchStatement extends TypedTransformationStatement {
    kind: "untilMatch";
    /**
     * The pattern to match.
     */
    pattern: TypedPattern;
    /**
     * Statements to execute until pattern matches.
     */
    doBlock: TypedTransformationStatement[];
}

/**
 * For-match statement.
 */
export interface TypedForMatchStatement extends TypedTransformationStatement {
    kind: "forMatch";
    /**
     * The pattern to match.
     */
    pattern: TypedPattern;
    /**
     * Statements to execute for each match.
     */
    doBlock: TypedTransformationStatement[];
}

/**
 * If-expression statement.
 */
export interface TypedIfExpressionStatement extends TypedTransformationStatement {
    kind: "ifExpression";
    /**
     * The condition expression.
     */
    condition: TypedExpression;
    /**
     * Statements to execute if condition is true.
     */
    thenBlock: TypedTransformationStatement[];
    /**
     * Array of else-if branches.
     */
    elseIfBranches: TypedElseIfBranch[];
    /**
     * Optional else block statements.
     */
    elseBlock?: TypedTransformationStatement[];
}

/**
 * Else-if branch.
 */
export interface TypedElseIfBranch {
    /**
     * The condition expression.
     */
    condition: TypedExpression;
    /**
     * Statements to execute if condition is true.
     */
    block: TypedTransformationStatement[];
}

/**
 * While-expression statement.
 */
export interface TypedWhileExpressionStatement extends TypedTransformationStatement {
    kind: "whileExpression";
    /**
     * The condition expression.
     */
    condition: TypedExpression;
    /**
     * Statements to execute while condition is true.
     */
    block: TypedTransformationStatement[];
}

/**
 * Stop statement.
 */
export interface TypedStopStatement extends TypedTransformationStatement {
    kind: "stop";
    /**
     * The keyword used (kill or stop).
     */
    keyword: string;
}

/**
 * Lambda expression.
 */
export interface TypedLambdaExpression extends TypedExpression {
    kind: "lambda";
    /**
     * Parameters of the lambda (names only, types are in the evalType).
     */
    parameters: string[];
    /**
     * Body of the lambda as a single expression.
     */
    body: TypedExpression;
}
