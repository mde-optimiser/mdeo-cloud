import {
    DefaultScope,
    IterableType,
    StatementsScopeProvider,
    inferLambdaTypeFromContext,
    extractMetamodelEntities,
    type BoundScope,
    type LambdaTypeInferenceResult,
    type Scope,
    type ScopeEntry,
    type ScopeLocalInitialization,
    type MetamodelEnumInfo,
    type MetamodelClassInfo,
    ReadonlyCollectionTypeFactory
} from "@mdeo/language-expression";
import type { ScriptTypirServices, ScriptTypirSpecifics } from "../../plugin.js";
import {
    expressionTypes,
    Function,
    LambdaExpression,
    Script,
    statementTypes,
    type FunctionType,
    type LambdaExpressionType,
    type ScriptType
} from "../../grammar/scriptTypes.js";
import { LambdaScope } from "./lambdaScope.js";
import { resolveRelativePath, sharedImport } from "@mdeo/language-shared";
import { getExportedEntitiesByPath } from "@mdeo/language-metamodel";

const { AstUtils } = sharedImport("langium");

/**
 * The scope provider for the Script language.
 * Adds support for function scopes, and the document scope
 */
export class ScriptScopeProvider extends StatementsScopeProvider<ScriptTypirSpecifics> {
    constructor(typir: ScriptTypirServices) {
        super(typir, statementTypes, expressionTypes, IterableType);
    }

    override isScopeRelevantNode(node: ScriptTypirSpecifics["LanguageType"]): boolean {
        return (
            super.isScopeRelevantNode(node) ||
            this.reflection.isInstance(node, Function) ||
            this.reflection.isInstance(node, Script) ||
            this.reflection.isInstance(node, LambdaExpression)
        );
    }

    override createScope(
        languageNode: ScriptTypirSpecifics["LanguageType"],
        parentScope: BoundScope<ScriptTypirSpecifics> | undefined
    ): Scope<ScriptTypirSpecifics> {
        if (this.reflection.isInstance(languageNode, Function)) {
            return this.createScopeForFunctionNode(languageNode, parentScope);
        } else if (this.reflection.isInstance(languageNode, Script)) {
            return this.createScopeForScriptNode(languageNode, parentScope);
        } else if (this.reflection.isInstance(languageNode, LambdaExpression)) {
            return this.createScopeForLambdaNode(languageNode, parentScope);
        }
        return super.createScope(languageNode, parentScope);
    }

    /**
     * Creates a scope for a function node.
     * Function scopes include the function's parameters as scope entries.
     *
     * @param node The function node to create a scope for
     * @param parentScope The parent scope of this function scope
     * @returns A new scope containing the function's parameters
     */
    private createScopeForFunctionNode(
        node: FunctionType,
        parentScope: BoundScope<ScriptTypirSpecifics> | undefined
    ): Scope<ScriptTypirSpecifics> {
        return new DefaultScope<ScriptTypirSpecifics>(
            parentScope,
            (scope) => this.getFunctionScopeEntries(node, scope),
            () => [],
            node.parameterList.parameters.map((param) => ({
                name: param.name,
                position: -1
            })),
            node
        );
    }

    /**
     * Retrieves the scope entries for a function node.
     * Each parameter of the function becomes a scope entry with its name, type, and defining scope.
     *
     * @param node The function node to get scope entries from
     * @param scope The scope that these entries belong to
     * @returns An array of scope entries, one for each function parameter
     */
    private getFunctionScopeEntries(
        node: FunctionType,
        scope: Scope<ScriptTypirSpecifics>
    ): ScopeEntry<ScriptTypirSpecifics>[] {
        return node.parameterList.parameters.map((param) => ({
            name: param.name,
            position: -1,
            languageNode: param,
            definingScope: scope,
            inferType: () => this.inference.inferType(param)
        }));
    }

    /**
     * Creates a scope for a script node.
     * Script scopes include all top-level functions defined in the script as scope entries,
     * plus enum and class singletons from the imported metamodel.
     *
     * @param node The script node to create a scope for
     * @param parentScope The parent scope of this script scope
     * @returns A new scope containing the script's functions and metamodel singletons
     */
    private createScopeForScriptNode(
        node: ScriptType,
        parentScope: BoundScope<ScriptTypirSpecifics> | undefined
    ): Scope<ScriptTypirSpecifics> {
        const { enumInfos, classInfos } = this.extractMetamodelInfos(node);
        return new DefaultScope<ScriptTypirSpecifics>(
            parentScope,
            (scope) => [
                ...this.getScriptScopeEntries(node, scope),
                ...this.getEnumContainerScopeEntries(enumInfos, scope),
                ...this.getClassContainerScopeEntries(classInfos, scope)
            ],
            () => [],
            [
                ...this.getScriptLocalInitializations(node),
                ...this.getEnumContainerInitializations(enumInfos),
                ...this.getClassContainerInitializations(classInfos)
            ],
            node
        );
    }

    /**
     * Extracts enum and class infos from the metamodel file referenced by the script's import statement.
     *
     * @param node The root Script node
     * @returns Object containing arrays of MetamodelEnumInfo and MetamodelClassInfo
     */
    private extractMetamodelInfos(node: ScriptType): {
        enumInfos: MetamodelEnumInfo[];
        classInfos: MetamodelClassInfo[];
    } {
        const importFile = node.metamodelImport?.file;
        if (importFile == undefined) {
            return { enumInfos: [], classInfos: [] };
        }

        const langiumServices = this.typir.langium.LangiumServices;
        const document = AstUtils.getDocument(node);
        const metamodelUri = resolveRelativePath(document, importFile);
        const documents = langiumServices.workspace.LangiumDocuments;
        const metamodelDoc = documents.getDocument(metamodelUri);
        if (metamodelDoc == undefined) {
            return { enumInfos: [], classInfos: [] };
        }

        const entitiesByPath = getExportedEntitiesByPath(metamodelDoc, documents);

        const allEnumInfos: MetamodelEnumInfo[] = [];
        const allClassInfos: MetamodelClassInfo[] = [];

        for (const docEntities of entitiesByPath) {
            const { classes: classInfos, enums: enumInfos } = extractMetamodelEntities(
                docEntities.classes,
                docEntities.enums,
                langiumServices.AstReflection,
                ReadonlyCollectionTypeFactory,
                docEntities.absolutePath
            );
            allEnumInfos.push(...enumInfos);
            allClassInfos.push(...classInfos);
        }

        return { enumInfos: allEnumInfos, classInfos: allClassInfos };
    }

    /**
     * Returns scope entries for all enum container types from the pre-extracted enum infos.
     * Creates scope entries for enum namespaces (e.g. `Status.ACTIVE`).
     *
     * @param enumInfos The pre-extracted enum infos
     * @param scope The scope to define entries in
     * @returns Array of scope entries for enum container types
     */
    private getEnumContainerScopeEntries(
        enumInfos: MetamodelEnumInfo[],
        scope: Scope<ScriptTypirSpecifics>
    ): ScopeEntry<ScriptTypirSpecifics>[] {
        return enumInfos.map(
            (enumInfo) =>
                ({
                    name: enumInfo.name,
                    definingScope: scope,
                    position: -1,
                    inferType: () =>
                        this.typir.TypeDefinitions.resolveCustomClassOrLambdaType({
                            package: enumInfo.containerPackage,
                            type: enumInfo.name,
                            isNullable: false
                        }),
                    readonly: true
                }) satisfies ScopeEntry<ScriptTypirSpecifics>
        );
    }

    /**
     * Returns local initializations for all enum container types.
     *
     * @param enumInfos The pre-extracted enum infos
     * @returns Array of local initialization records for enum containers
     */
    private getEnumContainerInitializations(enumInfos: MetamodelEnumInfo[]): ScopeLocalInitialization[] {
        return enumInfos.map((enumInfo) => ({ name: enumInfo.name, position: -1 }));
    }

    /**
     * Returns scope entries for all class container types from the pre-extracted class infos.
     * Creates scope entries for class namespaces (e.g. `Person.all()`).
     *
     * @param classInfos The pre-extracted class infos
     * @param scope The scope to define entries in
     * @returns Array of scope entries for class container types
     */
    private getClassContainerScopeEntries(
        classInfos: MetamodelClassInfo[],
        scope: Scope<ScriptTypirSpecifics>
    ): ScopeEntry<ScriptTypirSpecifics>[] {
        return classInfos.map(
            (classInfo) =>
                ({
                    name: classInfo.name,
                    definingScope: scope,
                    position: -1,
                    inferType: () =>
                        this.typir.TypeDefinitions.resolveCustomClassOrLambdaType({
                            package: classInfo.containerPackage,
                            type: classInfo.name,
                            isNullable: false
                        }),
                    readonly: true
                }) satisfies ScopeEntry<ScriptTypirSpecifics>
        );
    }

    /**
     * Returns local initializations for all class container types.
     *
     * @param classInfos The pre-extracted class infos
     * @returns Array of local initialization records for class containers
     */
    private getClassContainerInitializations(classInfos: MetamodelClassInfo[]): ScopeLocalInitialization[] {
        return classInfos.map((classInfo) => ({ name: classInfo.name, position: -1 }));
    }

    /**
     * Retrieves the scope entries for a script node.
     * Each function defined in the script becomes a scope entry with its name, type, and defining scope.
     *
     * @param node The script node to get scope entries from
     * @param scope The scope that these entries belong to
     * @returns An array of scope entries, one for each function in the script
     */
    private getScriptScopeEntries(
        node: ScriptType,
        scope: Scope<ScriptTypirSpecifics>
    ): ScopeEntry<ScriptTypirSpecifics>[] {
        const entries: ScopeEntry<ScriptTypirSpecifics>[] = [];
        for (const func of node.functions) {
            entries.push({
                name: func.name,
                position: -1,
                languageNode: func,
                definingScope: scope,
                inferType: () => this.inference.inferType(func)
            });
        }
        for (const importStatement of node.imports) {
            for (const functionImport of importStatement.imports) {
                const ref = functionImport.entity.ref;
                if (ref == undefined) {
                    continue;
                }
                entries.push({
                    name: functionImport.name ?? ref.name,
                    position: -1,
                    languageNode: ref,
                    definingScope: scope,
                    inferType: () => this.inference.inferType(ref)
                });
            }
        }
        return entries;
    }

    /**
     * Retrieves the local initializations for a script node.
     * Each top-level function and imported function becomes a local initialization.
     *
     * @param node The script node to get local initializations from
     * @returns An array of local initializations for the script
     */
    getScriptLocalInitializations(node: ScriptType): ScopeLocalInitialization[] {
        const initializations: ScopeLocalInitialization[] = [];
        for (const func of node.functions) {
            initializations.push({
                name: func.name,
                position: -1
            });
        }
        for (const importStatement of node.imports) {
            for (const functionImport of importStatement.imports) {
                const ref = functionImport.entity.ref;
                if (ref == undefined) {
                    continue;
                }
                initializations.push({
                    name: functionImport.name ?? ref.name,
                    position: -1
                });
            }
        }
        return initializations;
    }

    /**
     * Creates a scope for a lambda expression node.
     * Lambda scopes include the lambda's parameters as scope entries.
     * The scope stores the result of lambda type inference for later use during type checking.
     *
     * @param node The lambda expression node to create a scope for
     * @param parentScope The parent scope of this lambda scope
     * @returns A new LambdaScope containing the lambda's parameters and type inference result
     */
    private createScopeForLambdaNode(
        node: LambdaExpressionType,
        parentScope: BoundScope<ScriptTypirSpecifics> | undefined
    ): Scope<ScriptTypirSpecifics> {
        const lambdaTypeInference = inferLambdaTypeFromContext<ScriptTypirSpecifics>(
            node,
            this.typir,
            expressionTypes,
            statementTypes
        );

        return new LambdaScope(
            parentScope,
            (scope) => this.getLambdaScopeEntries(node, scope, lambdaTypeInference),
            () => [],
            node.parameterList.parameters.map((param) => ({
                name: param.name,
                position: -1
            })),
            node,
            lambdaTypeInference
        );
    }

    /**
     * Retrieves the scope entries for a lambda expression node.
     * Each parameter of the lambda becomes a scope entry with its name, type (inferred from context),
     * and defining scope.
     *
     * @param node The lambda expression node to get scope entries from
     * @param scope The scope that these entries belong to
     * @param lambdaTypeInference The result of lambda type inference
     * @returns An array of scope entries, one for each lambda parameter
     */
    private getLambdaScopeEntries(
        node: LambdaExpressionType,
        scope: Scope<ScriptTypirSpecifics>,
        lambdaTypeInference: LambdaTypeInferenceResult<ScriptTypirSpecifics>
    ): ScopeEntry<ScriptTypirSpecifics>[] {
        if (Array.isArray(lambdaTypeInference)) {
            return node.parameterList.parameters.map((param) => ({
                name: param.name,
                position: -1,
                languageNode: param,
                definingScope: scope,
                inferType: () => lambdaTypeInference
            }));
        }

        const parameterTypes =
            "type" in lambdaTypeInference
                ? lambdaTypeInference.type.details.parameterTypes
                : lambdaTypeInference.parameterTypes;

        return node.parameterList.parameters.map((param, index) => ({
            name: param.name,
            position: -1,
            languageNode: param,
            definingScope: scope,
            inferType: () => parameterTypes[index] ?? lambdaTypeInference
        }));
    }
}
