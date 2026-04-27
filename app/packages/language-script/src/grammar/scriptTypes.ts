import { createInterface, type ASTType, Optional, createType } from "@mdeo/language-common";
import {
    ExpressionConfig,
    generateExpressionTypes,
    TypeConfig,
    generateTypeTypes,
    StatementConfig,
    generateStatementTypes
} from "@mdeo/language-expression";
import { FileScopingConfig, generateImportTypes } from "@mdeo/language-shared";

/**
 * Configuration for the Script language types.
 */
export const typeConfig = new TypeConfig("Script");

/**
 * Generated type types for the Script language.
 */
export const typeTypes = generateTypeTypes(typeConfig);

/**
 * Configuration for the Script language expressions.
 */
export const expressionConfig = new ExpressionConfig("Script");

/**
 * Generated expression types for the Script language.
 */
export const expressionTypes = generateExpressionTypes(expressionConfig, typeTypes);

/**
 * Configuration for the Script language statements.
 */
export const statementConfig = new StatementConfig("Script");

/**
 * Generated statement types for the Script language.
 */
const baseStatementTypes = generateStatementTypes(statementConfig, expressionTypes);

/**
 * Return statement type for Script functions.
 */
export const ReturnStatement = createInterface("ScriptReturnStatement")
    .extends(baseStatementTypes.baseStatementType)
    .attrs({
        value: Optional(expressionTypes.baseExpressionType)
    });

/**
 * Type representing a ReturnStatement AST node.
 */
export type ReturnStatementType = ASTType<typeof ReturnStatement>;

/**
 * All statement types including Script-specific statements.
 */
export const statementTypes = {
    ...baseStatementTypes,
    returnStatementType: ReturnStatement
};

/**
 * Type representing the base expression type.
 */
export const BaseExpression = expressionTypes.baseExpressionType;

/**
 * Type representing the base statement type.
 */
export const BaseStatement = statementTypes.baseStatementType;

/**
 * Type representing the statements scope type.
 */
export const StatementsScope = statementTypes.statementsScopeType;

/**
 * Lambda parameter type.
 */
export const LambdaParameter = createInterface("ScriptLambdaParameter").attrs({
    name: String
});

/**
 * Lambda parameters list (with round brackets).
 */
export const LambdaParameters = createInterface("ScriptLambdaParameters").attrs({
    parameters: [LambdaParameter]
});

/**
 * Lambda expression type for Script.
 */
export const LambdaExpression = createInterface("ScriptLambdaExpression")
    .extends(BaseExpression)
    .attrs({
        parameterList: LambdaParameters,
        expression: Optional(BaseExpression),
        body: Optional(StatementsScope)
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
 * Base type for all Script extensions used in ExtensionExpressions.
 */
export const BaseExtension = createInterface("ScriptBaseExtension").extends(BaseExpression).attrs({});

/**
 * Extension expression type for Script.
 * Contributed by a plugin
 */
export const ExtensionExpression = createInterface("ScriptExtensionExpression").extends(BaseExpression).attrs({
    extension: BaseExtension
});

/**
 * Type representing an ExtensionExpression AST node.
 */
export type ExtensionExpressionType = ASTType<typeof ExtensionExpression>;

/**
 * Type representing a BaseExtension AST node.
 */
export type BaseExtensionType = ASTType<typeof BaseExtension>;

/**
 * Type representing the base type annotation.
 */
export const ScriptBaseType = typeTypes.baseTypeType;

/**
 * Parameter type for functions.
 */
export const FunctionParameter = createInterface("ScriptFunctionParameter").attrs({
    name: String,
    type: ScriptBaseType
});

/**
 * Function parameters list (with round brackets).
 */
export const FunctionParameters = createInterface("ScriptFunctionParameters").attrs({
    parameters: [FunctionParameter]
});

/**
 * Function type.
 */
export const Function = createInterface("ScriptFunction").attrs({
    name: String,
    parameterList: FunctionParameters,
    returnType: Optional(typeTypes.returnTypeType),
    body: StatementsScope
});

/**
 * Type representing a Function AST node.
 */
export type FunctionType = ASTType<typeof Function>;

/**
 * Type representing a FunctionParameters AST node.
 */
export type FunctionParametersType = ASTType<typeof FunctionParameters>;

/**
 * Type representing a FunctionParameter AST node.
 */
export type FunctionParameterType = ASTType<typeof FunctionParameter>;

/**
 * File scoping configuration for functions.
 */
export const scriptFileScopingConfig = new FileScopingConfig<FunctionType>("ScriptFunction", Function);

/**
 * Import types for functions.
 */
export const { importType: FunctionImport, fileImportType: FunctionFileImport } =
    generateImportTypes(scriptFileScopingConfig);

/**
 * Type representing a FunctionImport AST node.
 */
export type FunctionFileImportType = ASTType<typeof FunctionFileImport>;

/**
 * Type representing a FunctionImport AST node.
 */
export type FunctionImportType = ASTType<typeof FunctionImport>;

/**
 * Union type for Function or FunctionImport.
 */
export const FunctionOrImport = createType("ScriptFunctionOrImport").types(Function, FunctionImport);

/**
 * Type representing FunctionOrImport AST node.
 */
export type FunctionOrImportType = ASTType<typeof FunctionOrImport>;

/**
 * Metamodel file import for the script.
 */
export const MetamodelFileImport = createInterface("ScriptMetamodelFileImport").attrs({
    file: String
});

/**
 * Type representing a MetamodelFileImport AST node.
 */
export type MetamodelFileImportType = ASTType<typeof MetamodelFileImport>;

/**
 * The root Script type.
 */
export const Script = createInterface("Script").attrs({
    metamodelImport: Optional(MetamodelFileImport),
    imports: [FunctionFileImport],
    functions: [Function]
});

/**
 * Type representing the Script AST node.
 */
export type ScriptType = ASTType<typeof Script>;
