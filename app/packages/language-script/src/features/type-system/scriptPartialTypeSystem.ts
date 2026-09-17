import {
    FunctionSignature,
    PartialTypeSystem,
    ReturnInferenceAnalyzer,
    ReturnValidationAnalyzer,
    type CustomValueType,
    type CustomVoidType,
    type PrimitiveTypes,
    isCustomValueType,
    isCustomVoidType,
    isCustomLambdaType,
    type CustomFunctionType,
    GenericResolver,
    findCommonParentType
} from "@mdeo/language-expression";
import {
    expressionTypes,
    ExtensionExpression,
    Function,
    FunctionParameter,
    type FunctionParametersType,
    Record,
    LambdaExpression,
    Script,
    type FunctionType,
    type ScriptType,
    type MetamodelFileImportType,
    type FunctionFileImportType
} from "../../grammar/scriptTypes.js";
import { ScriptReturnStatementAccessor } from "./scriptReturnStatementAccessor.js";
import { createRecordFunctionType, describeRecordFields, getRecordPackage, RECORD_COPY_METHOD } from "../records.js";
import { LambdaScope } from "./lambdaScope.js";
import type { ScriptTypirServices, ScriptTypirSpecifics } from "../../plugin.js";
import type {
    ResolvedContributedExpression,
    ResolvedScriptContributionPlugins
} from "../../plugin/scriptContributionPlugin.js";
import { sharedImport, resolveRelativeDocument } from "@mdeo/language-shared";
import type { InferenceProblem, Type, ValidationProblemAcceptor } from "typir";
import { isMetamodelCompatible } from "@mdeo/language-metamodel";
import type { LangiumDocument, LangiumDocuments } from "langium";

const { isAstNode, AstUtils } = sharedImport("langium");

/**
 * Partial type system implementation for Script-specific AST nodes.
 * Handles validation rules for Script language constructs like functions with return types.
 *
 * @template Specifics The language-specific type system configuration extending TypirLangiumSpecifics
 */
export class ScriptPartialTypeSystem extends PartialTypeSystem<ScriptTypirSpecifics, Record<string, never>> {
    /**
     * Lookup map for extension expression functions contributed by plugins.
     */
    private readonly extensionExpressionFunctionLookup: Map<string, CustomFunctionType> = new Map();

    /**
     * Constructor for ScriptPartialTypeSystem.
     *
     * @param typir the typir services
     * @param primitives the primitive types
     * @param plugins the contribution plugins, used to extend the type system
     */
    constructor(
        typir: ScriptTypirServices,
        private readonly primitives: PrimitiveTypes,
        private readonly plugins: ResolvedScriptContributionPlugins
    ) {
        super(typir, {});
        for (const expression of this.plugins.expressions) {
            const functionType = typir.TypeDefinitions.resolveCustomFunctionType(
                {
                    signatures: {
                        [FunctionSignature.DEFAULT_SIGNATURE]: expression.signature
                    }
                },
                expression.name
            );
            this.extensionExpressionFunctionLookup.set(expression.name, functionType);
        }
    }

    override registerRules(): void {
        this.registerScriptNameConflictsValidationRule();
        this.registerScriptImportCompatibilityValidationRule();
        this.registerFunctionInferenceRule();
        this.registerRecordInferenceRule();
        this.registerRecordValidationRule();
        this.registerFunctionParameterInferenceRule();
        this.registerFunctionValidationRule();
        this.registerFunctionDuplicateParametersValidationRule();
        this.registerParameterDefaultValueValidationRule();
        this.registerLambdaInferenceRule();
        this.registerLambdaValidationRule();
        this.registerExtensionExpressionInferenceRule();
    }

    /**
     * Registers a validation rule for Script to detect name conflicts.
     * Validates that there are no naming conflicts between function definitions and imports.
     */
    private registerScriptNameConflictsValidationRule(): void {
        this.registerValidationRule(Script, (node, accept) => {
            const scriptNode = node as ScriptType;
            const functionNames = new Set<string>();
            const importNames = new Set<string>();

            for (const func of scriptNode.functions) {
                if (functionNames.has(func.name)) {
                    accept({
                        languageNode: func,
                        languageProperty: "name",
                        message: `Duplicate function name '${func.name}'.`,
                        severity: "error"
                    });
                }
                functionNames.add(func.name);
            }

            for (const record of scriptNode.records) {
                if (functionNames.has(record.name)) {
                    accept({
                        languageNode: record,
                        languageProperty: "name",
                        message: `Duplicate declaration name '${record.name}'.`,
                        severity: "error"
                    });
                }
                functionNames.add(record.name);
            }

            for (const fileImport of scriptNode.imports) {
                for (const namedImport of fileImport.imports) {
                    const importName = namedImport.name ?? namedImport.entity?.ref?.name;

                    if (importName == undefined) {
                        continue;
                    }

                    if (importNames.has(importName)) {
                        accept({
                            languageNode: namedImport,
                            message: `Duplicate import name '${importName}'.`,
                            severity: "error"
                        });
                    }
                    importNames.add(importName);

                    if (functionNames.has(importName)) {
                        accept({
                            languageNode: namedImport,
                            languageProperty: namedImport.name != undefined ? "name" : "entity",
                            message: `Name conflict: '${importName}' is already defined in this file.`,
                            severity: "error"
                        });
                    }

                    // A record is also a type, which is always referred to by its declared name.
                    if (
                        namedImport.name != undefined &&
                        namedImport.entity?.ref != undefined &&
                        this.astReflection.isInstance(namedImport.entity.ref, Record)
                    ) {
                        accept({
                            languageNode: namedImport,
                            languageProperty: "name",
                            message: `The record '${namedImport.entity.ref.name}' cannot be renamed when it is imported.`,
                            severity: "error"
                        });
                    }
                }
            }
        });
    }

    /**
     * Registers a validation rule for Script to check import metamodel compatibility.
     * Validates that imported files either have no `using` statement or have a compatible one.
     * If the imported file has a `using`, the current file must also have one.
     */
    private registerScriptImportCompatibilityValidationRule(): void {
        this.registerValidationRule(Script, (node, accept) => {
            const document = AstUtils.getDocument(node);
            const documents = this.typir.langium.LangiumServices.workspace.LangiumDocuments;

            const currentMetamodelImport = node.metamodelImport;

            for (const fileImport of node.imports) {
                this.validateImportMetamodelCompatibility(
                    fileImport,
                    currentMetamodelImport,
                    document,
                    documents,
                    accept
                );
            }
        });
    }

    /**
     * Validates that an imported file's metamodel is compatible with the current file's metamodel.
     *
     * @param fileImport The file import to validate
     * @param currentMetamodelImport The current file's metamodel import (may be undefined)
     * @param document The current document
     * @param documents The Langium documents service
     * @param accept The validation acceptor
     */
    private validateImportMetamodelCompatibility(
        fileImport: FunctionFileImportType,
        currentMetamodelImport: MetamodelFileImportType | undefined,
        document: LangiumDocument,
        documents: LangiumDocuments,
        accept: ValidationProblemAcceptor<ScriptTypirSpecifics>
    ): void {
        if (fileImport.file == undefined) {
            return;
        }
        const targetDoc = this.resolveDocument(document, fileImport.file, documents);
        if (targetDoc == undefined) {
            accept({
                languageNode: fileImport,
                languageProperty: "file",
                message: `Cannot resolve import path '${fileImport.file}'. The file does not exist or is not loaded.`,
                severity: "error"
            });
            return;
        }

        const targetRoot = targetDoc.parseResult?.value;
        if (targetRoot == undefined || targetRoot.$type !== Script.name) {
            return;
        }

        const targetScript = targetRoot as ScriptType;
        const targetMetamodelImport = targetScript.metamodelImport;

        if (targetMetamodelImport == undefined) {
            return;
        }

        if (currentMetamodelImport == undefined) {
            accept({
                languageNode: fileImport,
                languageProperty: "file",
                message: `Cannot import from '${fileImport.file}' which uses a metamodel. The current file must have a 'using' statement.`,
                severity: "error"
            });
            return;
        }

        const currentMetamodelDoc = this.resolveDocument(document, currentMetamodelImport.file, documents);
        const targetMetamodelDoc = this.resolveDocument(targetDoc, targetMetamodelImport.file, documents);

        if (currentMetamodelDoc == undefined || targetMetamodelDoc == undefined) {
            return;
        }

        if (!isMetamodelCompatible(targetMetamodelDoc, currentMetamodelDoc, documents)) {
            accept({
                languageNode: fileImport,
                languageProperty: "file",
                message: `The imported file '${fileImport.file}' uses a metamodel that is not compatible with the current file's metamodel.`,
                severity: "error"
            });
        }
    }

    /**
     * Resolves a relative path to a document.
     *
     * @param fromDocument The document from which to resolve
     * @param relativePath The relative path
     * @param documents The Langium documents service
     * @returns The resolved document, or undefined if not found
     */
    private resolveDocument(
        fromDocument: LangiumDocument,
        relativePath: string,
        documents: LangiumDocuments
    ): LangiumDocument | undefined {
        return resolveRelativeDocument(fromDocument, relativePath, documents);
    }

    /**
     * Registers a type inference rule for functions.
     * Infers the function's return type and parameter types based on their definitions.
     */
    private registerFunctionInferenceRule(): void {
        this.registerInferenceRule(Function, (node) => {
            let returnType: CustomValueType | CustomVoidType;
            if (node.returnType != undefined) {
                const inferredReturnType = this.inference.inferType(node.returnType);
                if (isCustomValueType(inferredReturnType) || isCustomVoidType(inferredReturnType)) {
                    returnType = inferredReturnType;
                } else {
                    returnType = this.primitives.Any.asNullable;
                }
            } else {
                returnType = this.typir.factory.CustomVoid.getOrCreate();
            }

            if (node.parameterList?.parameters == undefined) {
                return {
                    $problem: this.inferenceProblem,
                    languageNode: node,
                    location: "Function must have a parameter list.",
                    subProblems: []
                };
            }

            const parameters = node.parameterList.parameters.map((param) => {
                const inferredParamType = this.inference.inferType(param);
                const paramType = isCustomValueType(inferredParamType)
                    ? inferredParamType
                    : this.primitives.Any.asNullable;
                return {
                    name: param.name,
                    type: paramType.definition,
                    hasDefault: param.defaultValue != undefined
                };
            });

            return this.typir.factory.CustomFunctions.create({
                definition: {
                    signatures: {
                        "": {
                            returnType: returnType.definition,
                            parameters: parameters
                        }
                    }
                },
                name: node.name,
                typeArgs: new Map()
            });
        });
    }

    /**
     * Registers a type inference rule for records.
     * A record, used as a value, is its constructor: a function taking the fields and returning
     * a new record.
     */
    private registerRecordInferenceRule(): void {
        this.registerInferenceRule(Record, (node) => {
            const typePackage = getRecordPackage(AstUtils.getDocument(node).uri.path, node.name);
            const { fields, fieldType } = describeRecordFields(node, this.typir);
            return this.typir.factory.CustomFunctions.create({
                definition: createRecordFunctionType(typePackage, node.name, fields, fieldType, false),
                name: node.name,
                typeArgs: new Map()
            });
        });
    }

    /**
     * Registers validation rules for records.
     * Field names must be unique, may not be the name of the copy method, and a record may not have
     * the name of a class or enum of the metamodel it can see.
     */
    private registerRecordValidationRule(): void {
        this.registerValidationRule(Record, (node, accept) => {
            const fieldNames = new Set<string>();
            for (const field of node.parameterList?.parameters ?? []) {
                if (fieldNames.has(field.name)) {
                    accept({
                        languageNode: field,
                        languageProperty: "name",
                        message: `Duplicate field name '${field.name}'.`,
                        severity: "error"
                    });
                }
                fieldNames.add(field.name);
                if (field.name === RECORD_COPY_METHOD) {
                    accept({
                        languageNode: field,
                        languageProperty: "name",
                        message: `A record field cannot be named '${RECORD_COPY_METHOD}', which is the name of the method that copies a record.`,
                        severity: "error"
                    });
                }
            }

            const { packageMap } = this.typir.PackageMapCache.getDocumentPackageCache(AstUtils.getDocument(node));
            const metamodelPackages = [...(packageMap.get("class") ?? []), ...(packageMap.get("enum") ?? [])];
            if (
                metamodelPackages.some(
                    (typePackage) =>
                        this.typir.TypeDefinitions.getClassTypeIfExisting(node.name, typePackage) != undefined
                )
            ) {
                accept({
                    languageNode: node,
                    languageProperty: "name",
                    message: `Name conflict: '${node.name}' is already defined by the metamodel.`,
                    severity: "error"
                });
            }
        });
    }

    /**
     * Registers a type inference rule for function parameters.
     * Infers the type of function parameters based on their type annotations.
     */
    private registerFunctionParameterInferenceRule(): void {
        this.registerInferenceRule(FunctionParameter, (node) => {
            if (node.type != undefined) {
                const type = this.inference.inferType(node.type);
                if (Array.isArray(type)) {
                    return type[0];
                }
                return type;
            } else {
                return {
                    $problem: this.inferenceProblem,
                    languageNode: node,
                    location: "Function parameter must have a type.",
                    subProblems: []
                };
            }
        });
    }

    /**
     * Registers a validation rule for function return types.
     * Validates that all return statements in a function body match the declared return type,
     * and that all code paths return a value if the return type is not void.
     */
    private registerFunctionValidationRule(): void {
        const accessor = new ScriptReturnStatementAccessor<ScriptTypirSpecifics>(this.typir);

        this.registerValidationRule(Function, (node, accept) => {
            const functionNode = node as FunctionType;
            const bodyScope = this.typir.ScopeProvider.getScope(functionNode.body);
            if (bodyScope == undefined) {
                return;
            }

            let expectedReturnType: CustomValueType | CustomVoidType;
            let expectedReturnTypeLanguageNode: ScriptTypirSpecifics["LanguageType"];
            if (functionNode.returnType != undefined) {
                const inferredReturnType = this.inference.inferType(functionNode.returnType);
                if (Array.isArray(inferredReturnType)) {
                    return;
                }
                if (!isCustomValueType(inferredReturnType) && !isCustomVoidType(inferredReturnType)) {
                    accept({
                        languageNode: functionNode.returnType,
                        message: "Return type must be a valid value or void type.",
                        severity: "error"
                    });
                    return;
                }
                expectedReturnType = inferredReturnType;
                expectedReturnTypeLanguageNode = functionNode.returnType;
            } else {
                expectedReturnType = this.typir.factory.CustomVoid.getOrCreate();
                expectedReturnTypeLanguageNode = functionNode;
            }

            const analyzer = new ReturnValidationAnalyzer<ScriptTypirSpecifics>(
                bodyScope,
                expectedReturnType,
                expectedReturnTypeLanguageNode,
                this.typir,
                accessor
            );

            for (const error of analyzer.errors) {
                accept(error);
            }
        });
    }

    /**
     * Registers a validation rule for duplicate function parameters.
     * Validates that no function has duplicate parameter names.
     */
    private registerFunctionDuplicateParametersValidationRule(): void {
        this.registerValidationRule(Function, (node, accept) => {
            const parameterNames = new Set<string>();
            for (const param of node.parameterList?.parameters ?? []) {
                if (parameterNames.has(param.name)) {
                    accept({
                        languageNode: param,
                        message: `Duplicate parameter name '${param.name}'.`,
                        severity: "error"
                    });
                }
                parameterNames.add(param.name);
            }
        });
    }

    /**
     * Registers a validation rule for default values of parameters.
     * A default value must be assignable to the parameter's type, and may only refer to the
     * parameters declared before it.
     */
    private registerParameterDefaultValueValidationRule(): void {
        this.registerValidationRule(FunctionParameter, (node, accept) => {
            const defaultValue = node.defaultValue;
            if (defaultValue == undefined) {
                return;
            }
            const valueType = this.inference.inferType(defaultValue);
            const parameterType = this.inference.inferType(node);
            if (
                !Array.isArray(valueType) &&
                !Array.isArray(parameterType) &&
                !this.typir.Assignability.isAssignable(valueType, parameterType)
            ) {
                accept({
                    languageNode: defaultValue,
                    message: `Default value of type '${valueType.getName()}' is not assignable to parameter type '${parameterType.getName()}'.`,
                    severity: "error"
                });
            }

            const parameters = (node.$container as FunctionParametersType).parameters;
            const ownIndex = parameters.indexOf(node);
            for (const child of AstUtils.streamAst(defaultValue)) {
                if (!this.astReflection.isInstance(child, expressionTypes.identifierExpressionType)) {
                    continue;
                }
                const entry = this.typir.ScopeProvider.getScope(child).getEntry(child.name);
                const referenced = entry?.languageNode;
                if (referenced == undefined || !this.astReflection.isInstance(referenced, FunctionParameter)) {
                    continue;
                }
                const referencedIndex = parameters.indexOf(referenced);
                if (referencedIndex >= ownIndex) {
                    accept({
                        languageNode: child,
                        message: `The default value of '${node.name}' can only refer to parameters declared before it.`,
                        severity: "error"
                    });
                }
            }
        });
    }

    /**
     * Registers a type inference rule for lambda expressions.
     * Uses a two-step inference process:
     * 1. Get the lambda scope and its type inference result
     * 2. If the result is complete, use it; otherwise infer the return type from the lambda body
     */
    private registerLambdaInferenceRule(): void {
        this.registerInferenceRule(LambdaExpression, (node) => {
            const scope = this.typir.ScopeProvider.getScope(node).scope;

            if (!(scope instanceof LambdaScope)) {
                return {
                    $problem: this.inferenceProblem,
                    languageNode: node,
                    location: "Lambda scope could not be created.",
                    subProblems: []
                };
            }

            const lambdaTypeInference = scope.lambdaTypeInference;

            if (Array.isArray(lambdaTypeInference)) {
                return lambdaTypeInference[0];
            }

            if ("type" in lambdaTypeInference) {
                return lambdaTypeInference.type;
            }

            const parameterTypes = lambdaTypeInference.parameterTypes;

            let returnType: CustomValueType | CustomVoidType;
            if (node.expression != undefined) {
                const expressionType = this.inference.inferType(node.expression);
                if (Array.isArray(expressionType)) {
                    return expressionType[0];
                }
                if (!isCustomValueType(expressionType) && !isCustomVoidType(expressionType)) {
                    return {
                        $problem: this.inferenceProblem,
                        languageNode: node.expression,
                        location: "Lambda expression must return a value type.",
                        subProblems: []
                    };
                }
                returnType = expressionType;
            } else if (node.body != undefined) {
                const accessor = new ScriptReturnStatementAccessor<ScriptTypirSpecifics>(this.typir);
                const bodyScope = this.typir.ScopeProvider.getScope(node.body);
                if (bodyScope == undefined) {
                    return {
                        $problem: this.inferenceProblem,
                        languageNode: node.body,
                        location: "Lambda body scope could not be created.",
                        subProblems: []
                    };
                }

                const analyzer = new ReturnInferenceAnalyzer<ScriptTypirSpecifics>(bodyScope, this.typir, accessor);

                if (analyzer.errors.length > 0) {
                    return analyzer.errors[0];
                }

                if (analyzer.returnType == undefined) {
                    returnType = this.typir.factory.CustomVoid.getOrCreate();
                } else {
                    returnType = analyzer.returnType;
                }
            } else {
                return {
                    $problem: this.inferenceProblem,
                    languageNode: node,
                    location: "Lambda must have either an expression or a body.",
                    subProblems: []
                };
            }

            return this.typir.factory.CustomLambdas.getOrCreate({
                returnType,
                parameterTypes,
                typeArgs: new Map(),
                isNullable: false
            });
        });
    }

    /**
     * Registers validation rules for lambda expressions.
     * Validates:
     * - Return types in lambda blocks match the inferred return type
     * - No too many parameters (compared to expected type)
     * - No duplicate parameter names
     */
    private registerLambdaValidationRule(): void {
        const accessor = new ScriptReturnStatementAccessor<ScriptTypirSpecifics>(this.typir);

        this.registerValidationRule(LambdaExpression, (node, accept) => {
            const scope = this.typir.ScopeProvider.getScope(node).scope;

            if (!(scope instanceof LambdaScope)) {
                accept({
                    languageNode: node,
                    message:
                        "Lambda scope could not be created in a scope where its type cannot be inferred automatically.",
                    severity: "error"
                });
                return;
            }

            const lambdaTypeInference = scope.lambdaTypeInference;

            const parameterNames = new Set<string>();
            for (const param of node.parameterList.parameters) {
                if (parameterNames.has(param.name)) {
                    accept({
                        languageNode: param,
                        message: `Duplicate parameter name '${param.name}'.`,
                        severity: "error"
                    });
                }
                parameterNames.add(param.name);
            }

            if (Array.isArray(lambdaTypeInference)) {
                accept({
                    languageNode: node,
                    message: "Could not infer lambda type.",
                    severity: "error",
                    subProblems: lambdaTypeInference
                });
                return;
            }

            const expectedParamCount =
                "type" in lambdaTypeInference
                    ? lambdaTypeInference.type.details.parameterTypes.length
                    : lambdaTypeInference.parameterTypes.length;

            if (node.parameterList.parameters.length > expectedParamCount) {
                accept({
                    languageNode: node,
                    message: `Lambda has too many parameters. Expected ${expectedParamCount}, got ${node.parameterList.parameters.length}.`,
                    severity: "error"
                });
            }

            const inferredLambdaType = this.inference.inferType(node);
            if (Array.isArray(inferredLambdaType)) {
                return;
            }
            if (!isCustomLambdaType(inferredLambdaType)) {
                return;
            }

            const expectedReturnType = inferredLambdaType.details.returnType;

            if (node.body != undefined) {
                const bodyScope = this.typir.ScopeProvider.getScope(node.body);
                if (bodyScope == undefined) {
                    return;
                }

                const analyzer = new ReturnValidationAnalyzer<ScriptTypirSpecifics>(
                    bodyScope,
                    expectedReturnType,
                    node,
                    this.typir,
                    accessor
                );

                for (const error of analyzer.errors) {
                    accept(error);
                }
            } else if (node.expression != undefined) {
                const expressionType = this.inference.inferType(node.expression);
                if (Array.isArray(expressionType)) {
                    return;
                }
                if (!isCustomValueType(expressionType) && !isCustomVoidType(expressionType)) {
                    accept({
                        languageNode: node.expression,
                        message: "Lambda expression must return a value type.",
                        severity: "error"
                    });
                    return;
                }

                if (!this.typir.Assignability.isAssignable(expressionType, expectedReturnType)) {
                    accept({
                        languageNode: node.expression,
                        message: `Return type '${expressionType.getName()}' is not assignable to expected return type '${expectedReturnType.getName()}'.`,
                        severity: "error"
                    });
                }
            }
        });
    }

    /**
     * Registers inference rule for extension expressions contributed by plugins.
     */
    private registerExtensionExpressionInferenceRule(): void {
        this.registerBaseExtensionExpressionInferenceRule();

        for (const expression of this.plugins.expressions) {
            const functionType = this.extensionExpressionFunctionLookup.get(expression.name)!;
            const signature = expression.signature;

            this.registerPluginExpressionInferenceRule(expression, functionType, signature);

            this.registerPluginExpressionValidationRule(expression, functionType, signature);
        }
    }

    /**
     * Registers the base inference rule for ExtensionExpression nodes.
     * This rule delegates type inference to the contained extension node.
     */
    private registerBaseExtensionExpressionInferenceRule(): void {
        this.registerInferenceRule(ExtensionExpression, (node) => {
            const type = this.inference.inferType(node.extension);
            if (Array.isArray(type)) {
                return type[0];
            }
            return type;
        });
    }

    /**
     * Registers the inference rule for a plugin-contributed extension expression.
     *
     * @param expression The plugin expression metadata
     * @param functionType The function type definition for the extension
     * @param signature The function signature
     * @returns The inferred type or an inference problem
     */
    private registerPluginExpressionInferenceRule(
        expression: ResolvedContributedExpression,
        functionType: CustomFunctionType,
        signature: FunctionSignature
    ) {
        this.registerInferenceRule(expression.interface, (node) => {
            const genericResolver = new GenericResolver<ScriptTypirSpecifics>(
                functionType,
                signature,
                false,
                this.typir,
                []
            );
            const initialResolved = genericResolver.resolveType(signature.returnType);
            if (!Array.isArray(initialResolved)) {
                return initialResolved;
            }
            for (let i = 0; i < signature.parameters.length; i++) {
                const name = signature.parameters[i].name;
                const argNode = node[name];
                let type: CustomValueType;
                if (Array.isArray(argNode)) {
                    const types = argNode
                        .filter(isAstNode)
                        .map((arg) => this.inferExtensionEntry(arg))
                        .filter(isCustomValueType);
                    type = this.createListTypeFromArguments(types);
                } else {
                    const potentialType = this.inferExtensionEntry(argNode);
                    if (!isCustomValueType(potentialType)) {
                        continue;
                    }
                    type = potentialType;
                }
                genericResolver.checkAndUpdateArgumentType(i, type);
            }
            const resolvedAfterArgs = genericResolver.resolveType(signature.returnType);
            if (!Array.isArray(resolvedAfterArgs)) {
                return resolvedAfterArgs;
            }
            return {
                $problem: this.inferenceProblem,
                languageNode: node,
                location: `Could not fully infer type for extension expression '${expression.name}'.`,
                subProblems: []
            };
        });
    }

    /**
     * Validates a plugin-contributed extension expression.
     * Checks that all arguments have valid types and are assignable to the expected parameter types.
     *
     * @param node The extension expression AST node
     * @param accept The validation acceptor for reporting errors
     * @param expression The plugin expression metadata
     * @param functionType The function type definition for the extension
     * @param signature The function signature
     */
    private registerPluginExpressionValidationRule(
        expression: ResolvedContributedExpression,
        functionType: CustomFunctionType,
        signature: FunctionSignature
    ): void {
        this.registerValidationRule(expression.interface, (node, accept) => {
            const genericResolver = new GenericResolver<ScriptTypirSpecifics>(
                functionType,
                signature,
                false,
                this.typir,
                []
            );
            let hasInnerInferenceProblems = false;
            for (let i = 0; i < signature.parameters.length; i++) {
                const name = signature.parameters[i].name;
                const argNode = node[name];
                let type: CustomValueType;
                if (Array.isArray(argNode)) {
                    const types = argNode.map((arg) => this.inference.inferType(arg));
                    let hasListProblems = false;
                    for (const type of types) {
                        if (Array.isArray(type)) {
                            hasListProblems = true;
                        } else if (!isCustomValueType(type)) {
                            hasListProblems = true;
                            accept({
                                languageNode: isAstNode(argNode) ? argNode : node,
                                message: `Argument '${name}' does not have a valid value type.`,
                                severity: "error"
                            });
                        }
                    }
                    if (hasListProblems) {
                        hasInnerInferenceProblems = true;
                        continue;
                    } else {
                        type = this.createListTypeFromArguments(types as CustomValueType[]);
                    }
                } else {
                    const potentialType = this.inferExtensionEntry(argNode);
                    if (Array.isArray(potentialType)) {
                        hasInnerInferenceProblems = true;
                        continue;
                    }
                    if (!isCustomValueType(potentialType)) {
                        accept({
                            languageNode: isAstNode(argNode) ? argNode : node,
                            message: `Argument '${name}' does not have a valid value type.`,
                            severity: "error"
                        });
                        continue;
                    }
                    type = potentialType;
                }
                if (!genericResolver.checkAndUpdateArgumentType(i, type)) {
                    accept({
                        languageNode: isAstNode(argNode) ? argNode : node,
                        message: `Argument '${name}' of type '${type.getName()}' is not assignable.`,
                        severity: "error"
                    });
                }
            }
            if (!hasInnerInferenceProblems) {
                const type = this.inference.inferType(node);
                if (Array.isArray(type)) {
                    accept({
                        languageNode: node,
                        message: `Could not infer type for extension expression '${expression.name}'.`,
                        severity: "error",
                        subProblems: type
                    });
                }
            }
        });
    }

    /**
     * Infers the type of an extension enpression entry value
     *
     * @param value the entry value to infer the type for
     * @returns the inferred type or an inference problem or undefined if inference for the type is not possible
     */
    private inferExtensionEntry(value: unknown): Type | InferenceProblem<ScriptTypirSpecifics>[] | undefined {
        if (value == undefined) {
            return this.typir.factory.CustomNull.getOrCreate();
        } else if (isAstNode(value)) {
            return this.inference.inferType(value);
        } else if (typeof value === "string") {
            return this.primitives.string;
        } else if (typeof value === "number") {
            return this.primitives.double;
        } else if (typeof value === "boolean") {
            return this.primitives.boolean;
        } else {
            return undefined;
        }
    }

    /**
     * Creates a List type from an array of argument nodes.
     * Infers the type of each argument and finds their common parent type.
     *
     * @param args The array of argument nodes to infer types from
     * @returns A List type with the common parent type as the type argument
     */
    private createListTypeFromArguments(types: CustomValueType[]): CustomValueType {
        return this.typir.TypeDefinitions.resolveCustomClassOrLambdaType({
            package: "builtin",
            type: "List",
            isNullable: false,
            typeArgs: {
                T: this.findCommonListType(types).definition
            }
        });
    }

    /**
     * Finds the common parent type for a list of types.
     * If the list is empty, returns `Any?`.
     *
     * @param types The list of types to find the common parent for
     * @returns The common parent type
     */
    private findCommonListType(types: CustomValueType[]): CustomValueType {
        if (types.length === 0) {
            return this.primitives.Any.asNullable;
        }
        return types.reduce<CustomValueType>((acc, current) => {
            return findCommonParentType(acc, current, this.typir);
        }, types[0]);
    }
}
