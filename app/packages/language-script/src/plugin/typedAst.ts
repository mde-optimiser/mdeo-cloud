import type { ReturnType } from "@mdeo/language-expression";
import { type TypedExpression, type TypedCallableBody } from "@mdeo/language-expression";

export type { TypedCallableBody } from "@mdeo/language-expression";

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
     * Optional absolute path of the metamodel file referenced by this script.
     * Undefined if the script has no metamodel import.
     */
    metamodelPath?: string;

    /**
     * All imports in the program.
     */
    imports: TypedImport[];

    /**
     * All top-level functions in the program.
     */
    functions: TypedFunction[];

    /**
     * All records the program declares.
     */
    records: TypedRecord[];
}

/**
 * Record declaration.
 */
export interface TypedRecord {
    /**
     * Name of the record, which is also the name of its constructor.
     */
    name: string;

    /**
     * The type package of the record, as types in the types array refer to it.
     */
    package: string;

    /**
     * Index into the types array for the (non-nullable) record type, which its constructor returns.
     */
    type: number;

    /**
     * The fields of the record in declaration order, with their default values.
     */
    fields: TypedParameter[];
}

/**
 * Import declaration.
 */
export interface TypedImport {
    /**
     * Name under which it is registered in the global scope.
     */
    name: string;

    /**
     * The name of the function in its source file.
     */
    ref: string;

    /**
     * URI from where it is imported.
     */
    uri: string;
}

/**
 * Function declaration.
 */
export interface TypedFunction {
    /**
     * Name of the function.
     */
    name: string;

    /**
     * Parameters of the function.
     */
    parameters: TypedParameter[];

    /**
     * Index into the types array for the return type.
     */
    returnType: number;

    /**
     * Body of the function.
     */
    body: TypedCallableBody;
}

/**
 * Function or lambda parameter.
 */
export interface TypedParameter {
    /**
     * Name of the parameter.
     */
    name: string;

    /**
     * Index into the types array for the parameter type.
     */
    type: number;

    /**
     * The value the parameter takes when a call leaves it out. It is evaluated at every such call,
     * after the arguments, and may refer to the parameters declared before it.
     */
    defaultValue?: TypedExpression;
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
     * Body of the lambda as statements.
     */
    body: TypedCallableBody;
    /**
     * Whether {@link body} is the lambda's own block. False when the lambda was written with an
     * expression body, in which case {@link body} only wraps that expression in a synthetic return
     * statement.
     *
     * A block body is a scope of its own and so raises the level of every scope inside it by one,
     * while an expression body is not. Since the `scope` of an identifier is a level, the compiler
     * has to be told which of the two shapes it is looking at to rebuild the same levels.
     */
    hasBlockBody: boolean;
}
