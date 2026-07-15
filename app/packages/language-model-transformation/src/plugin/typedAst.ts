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
     * Optional modifier (create, delete, forbid, or require).
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
     * Optional modifier (create, delete, forbid, or require).
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
 * Pattern containing pattern elements.
 */
export interface TypedPattern {
    /**
     * Pattern elements.
     */
    elements: (
        | TypedPatternVariableElement
        | TypedPatternObjectInstanceElement
        | TypedPatternLinkElement
        | TypedPatternWhereClauseElement
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
