import type {
    ExtensionExpressionType,
    ResolvedContributedExpression,
    ScriptTypirServices
} from "@mdeo/language-script";
import { ExtensionExpression } from "@mdeo/language-script";
import type {
    TypedAst,
    TypedFunction,
    TypedParameter,
    TypedLambdaExpression,
    TypedImport,
    TypedRecord
} from "@mdeo/language-script";
import { getRecordPackage } from "@mdeo/language-script";
import type { ScriptType, FunctionType, LambdaExpressionType, FunctionParametersType } from "@mdeo/language-script";
import { LambdaExpression, statementTypes, expressionTypes } from "@mdeo/language-script";
import type {
    TypedExpression,
    TypedCallableBody,
    TypedReturnStatement,
    TypedExtensionCallExpression,
    TypedExtensionCallArgument,
    TypedStringLiteralExpression,
    TypedDoubleLiteralExpression,
    TypedBooleanLiteralExpression,
    TypedStatement
} from "@mdeo/language-expression";
import { StatementTypedAstConverter } from "@mdeo/language-expression";
import { GrammarUtils, isAstNode, type AstNode, type LangiumDocument } from "langium";
import type { AstReflection } from "@mdeo/language-common";
import type { ReturnStatementType } from "@mdeo/language-script";
import { resolveRelativePath } from "@mdeo/language-shared";

/**
 * Script-specific extension of StatementTypedAstConverter.
 * Handles script-specific features like functions, imports, lambdas, extension calls, and return statements.
 */
export class ScriptTypedAstConverter extends StatementTypedAstConverter {
    /**
     * Lookup of resolved contributed expressions by their type name
     */
    private extensionTypeLookup = new Map<string, ResolvedContributedExpression>();

    /**
     * Statement type identifiers for checking instance types.
     */
    protected statementTypes = statementTypes;

    /**
     * Expression type identifiers for checking instance types.
     */
    protected expressionTypes = expressionTypes;

    /**
     * Creates a new ScriptTypedAstConverter.
     *
     * @param typir The Typir services for type inference
     * @param reflection The AST reflection for type checking
     */
    constructor(
        protected override readonly typir: ScriptTypirServices,
        reflection: AstReflection
    ) {
        super(typir, reflection);
        for (const expression of typir.ResolvedContributionPlugins.expressions) {
            this.extensionTypeLookup.set(expression.interface.name, expression);
        }
    }

    /**
     * Converts a Script AST node to a TypedAst.
     *
     * @param script The Script AST node
     * @param document The LangiumDocument for resolving relative paths
     * @returns The TypedAst representation
     */
    convertScript(script: ScriptType, document: LangiumDocument): TypedAst {
        const imports: TypedImport[] = script.imports.flatMap((imp) =>
            imp.imports.map((fun) => ({
                name: fun.name ?? fun.entity.$refText,
                ref: fun.entity.$refText,
                uri: resolveRelativePath(document, imp.file).path
            }))
        );

        const functions: TypedFunction[] = script.functions.map((func: FunctionType) => this.convertFunction(func));

        const records: TypedRecord[] = script.records.map((record) => {
            const recordPackage = getRecordPackage(document.uri.path, record.name);
            return {
                name: record.name,
                package: recordPackage,
                type: this.getTypeIndexForType(
                    this.typir.TypeDefinitions.resolveCustomClassOrLambdaType({
                        package: recordPackage,
                        type: record.name,
                        isNullable: false
                    })
                ),
                fields: this.convertParameters(record.parameterList)
            };
        });

        const metamodelPath =
            script.metamodelImport?.file != null
                ? resolveRelativePath(document, script.metamodelImport.file).path
                : undefined;

        return {
            types: this.types,
            metamodelPath,
            imports,
            functions,
            records
        };
    }

    /**
     * Converts a Function AST node to a TypedFunction.
     *
     * @param func The Function AST node
     * @returns The TypedFunction representation
     */
    private convertFunction(func: FunctionType): TypedFunction {
        const parameters = this.convertParameters(func.parameterList);
        const returnTypeIndex = func.returnType != undefined ? this.getTypeIndex(func.returnType) : this.voidTypeIndex;
        const body = this.convertCallableBody(func.body);

        return {
            name: func.name,
            parameters,
            returnType: returnTypeIndex,
            body
        };
    }

    /**
     * Converts function parameters to typed parameters.
     *
     * @param paramList The function parameters list
     * @returns Array of typed parameters
     */
    private convertParameters(paramList: FunctionParametersType): TypedParameter[] {
        return paramList.parameters.map((param) =>
            param.defaultValue != undefined
                ? {
                      name: param.name,
                      type: this.getTypeIndex(param),
                      defaultValue: this.convertExpression(param.defaultValue)
                  }
                : {
                      name: param.name,
                      type: this.getTypeIndex(param)
                  }
        );
    }

    /**
     * Handles script-specific expression types (lambda, extensionCall).
     * Overrides the base class hook for additional expressions.
     *
     * @param expr The expression AST node
     * @returns The TypedExpression representation
     */
    protected override convertAdditionalExpression(expr: AstNode): TypedExpression {
        if (this.reflection.isInstance(expr, LambdaExpression)) {
            return this.convertLambdaExpression(expr);
        } else if (this.reflection.isInstance(expr, ExtensionExpression)) {
            return this.convertExtensionExpression(expr);
        }
        return super.convertAdditionalExpression(expr);
    }

    /**
     * Handles script-specific statement types (return statement).
     * Overrides the base class hook for additional statements.
     *
     * @param statement The statement AST node
     * @returns The TypedStatement representation
     */
    protected override convertAdditionalStatement(statement: AstNode): TypedStatement {
        if (this.reflection.isInstance(statement, this.statementTypes.returnStatementType)) {
            return this.convertReturnStatement(statement as ReturnStatementType);
        }
        return super.convertAdditionalStatement(statement);
    }

    /**
     * Converts a return statement.
     *
     * @param statement The return statement AST node
     * @returns The TypedReturnStatement representation
     */
    private convertReturnStatement(statement: ReturnStatementType): TypedReturnStatement {
        return {
            kind: "return",
            value: statement.value ? this.convertExpression(statement.value) : undefined
        };
    }

    /**
     * Converts a lambda expression.
     *
     * @param expr The lambda expression AST node
     * @returns The TypedLambdaExpression representation
     */
    private convertLambdaExpression(expr: LambdaExpressionType): TypedLambdaExpression {
        const parameters = expr.parameterList.parameters.map((param: any) => param.name);

        let body: TypedCallableBody;
        let hasBlockBody: boolean;
        if (expr.expression != undefined) {
            const returnStatement: TypedReturnStatement = {
                kind: "return",
                value: this.convertExpression(expr.expression)
            };
            body = {
                body: [returnStatement]
            };
            hasBlockBody = false;
        } else if (expr.body != undefined) {
            body = this.convertCallableBody(expr.body);
            hasBlockBody = true;
        } else {
            body = { body: [] };
            hasBlockBody = false;
        }

        return {
            kind: "lambda",
            evalType: this.getTypeIndex(expr),
            parameters,
            body,
            hasBlockBody
        };
    }

    /**
     * Converts an extension expression.
     *
     * @param expr The extension expression AST node
     * @returns The TypedExtensionCallExpression representation
     */
    private convertExtensionExpression(expr: ExtensionExpressionType): TypedExtensionCallExpression {
        const extension = expr.extension;
        const config = this.extensionTypeLookup.get(extension.$type);
        if (config == undefined) {
            throw new Error(`Extension expression type '${extension.$type}' not found`);
        }
        if (!this.reflection.isInstance(extension, config.interface)) {
            throw new Error(`Extension expression is not an instance of '${config.interface.name}'`);
        }
        const args: { arg: TypedExtensionCallArgument; position: number }[] = [];
        for (const param of config.signature.parameters) {
            const argValue = extension[param.name];
            if (Array.isArray(argValue)) {
                for (let i = 0; i < argValue.length; i++) {
                    const cstNode = GrammarUtils.findNodeForProperty(extension.$cstNode, param.name, i);
                    const argExpr = this.convertExtensionArgument(argValue[i]);
                    args.push({
                        arg: { name: param.name, value: argExpr },
                        position: cstNode?.offset ?? -1
                    });
                }
            } else {
                const cstNode = GrammarUtils.findNodeForProperty(extension.$cstNode, param.name);
                const argExpr = this.convertExtensionArgument(argValue);
                args.push({
                    arg: { name: param.name, value: argExpr },
                    position: cstNode?.offset ?? -1
                });
            }
        }
        args.sort((a, b) => a.position - b.position);

        return {
            kind: "extensionCall",
            evalType: this.getTypeIndex(extension),
            arguments: args.map((a) => a.arg),
            name: config.name,
            overload: ""
        };
    }

    /**
     * Converts an extension argument value to a TypedExpression.
     *
     * @param value The extension argument value
     * @returns The TypedExpression representation
     * @throws Error if the value type is unsupported
     */
    private convertExtensionArgument(value: unknown): TypedExpression {
        if (value == undefined) {
            return {
                kind: "nullLiteral",
                evalType: this.nullTypeIndex
            };
        }
        if (isAstNode(value)) {
            return this.convertExpression(value);
        } else if (typeof value === "string") {
            const stringExp: TypedStringLiteralExpression = {
                kind: "stringLiteral",
                evalType: this.stringTypeIndex,
                value
            };
            return stringExp;
        } else if (typeof value === "number") {
            const doubleExp: TypedDoubleLiteralExpression = {
                kind: "doubleLiteral",
                evalType: this.doubleTypeIndex,
                value: value.toString()
            };
            return doubleExp;
        } else if (typeof value === "boolean") {
            const boolExp: TypedBooleanLiteralExpression = {
                kind: "booleanLiteral",
                evalType: this.booleanTypeIndex,
                value
            };
            return boolExp;
        } else {
            throw new Error(`Unsupported extension argument type: ${value}`);
        }
    }
}
