import {
    type ActionStartParams,
    type ActionStartResponse,
    type ActionSubmitParams,
    type ActionSubmitResponse,
    type ActionSchema,
    type ActionExecutionRequest,
    type FileMenuActionData,
    parseUri,
    FileCategory,
    describeValidationErrors
} from "@mdeo/language-common";
import { sharedImport, type ActionHandler, resolveRelativePath, buildFileSelectTree } from "@mdeo/language-shared";
import { isMetamodelCompatible } from "@mdeo/language-metamodel";
import type { ExtendedLangiumSharedServices } from "@mdeo/language-common";
import { type ScriptType } from "../grammar/scriptTypes.js";
import type { ModelType } from "@mdeo/language-model";

const { URI } = sharedImport("langium");

/**
 * Input data collected from the dialog.
 */
interface MethodSelectionInputs {
    /**
     * The name of the selected method to execute.
     */
    methodName: string;
}

/**
 * Input data collected when both method and model selection are shown.
 */
interface MethodAndModelSelectionInputs {
    /**
     * The name of the selected method to execute.
     */
    methodName: string;
    /**
     * Optional relative path to the selected model file.
     */
    modelPath?: string;
}

/**
 * Type guard to check if a value is MethodSelectionInputs.
 *
 * @param value The value to check
 * @returns True if the value is MethodSelectionInputs
 */
function isMethodSelectionInputs(value: unknown): value is MethodSelectionInputs {
    return typeof value === "object" && value !== null && "methodName" in value;
}

/**
 * Handler for the "run" action in the Script language.
 *
 * This handler:
 *  - Parses the script file to extract all functions
 *  - Filters to only show parameterless functions (since execution only supports those)
 *  - Presents them as a dropdown selection
 *  - When selected, creates an execution request with the method name
 */
export class RunScriptActionHandler implements ActionHandler {
    /**
     * Shared Langium services for accessing workspace documents.
     */
    private readonly sharedServices: ExtendedLangiumSharedServices;

    /**
     * Creates a new run script action handler.
     *
     * @param sharedServices Shared Langium services
     */
    constructor(sharedServices: ExtendedLangiumSharedServices) {
        this.sharedServices = sharedServices;
    }

    /**
     * Starts the run action dialog.
     *
     * This method:
     *  - Stores the file URI
     *  - Parses the script file to find all functions
     *  - Filters to only parameterless functions
     *  - Presents those functions in a dropdown
     *
     * @param params The action start parameters with file URI data
     * @returns The dialog page with method selection or immediate completion
     */
    async startAction(params: ActionStartParams): Promise<ActionStartResponse> {
        const data = params.data as FileMenuActionData;

        const validationError = this.checkValidationErrors(URI.parse(data.uri).toString());
        if (validationError != undefined) {
            return {
                kind: "page",
                page: {
                    title: "Run Script Method",
                    description: validationError,
                    schema: { properties: {} },
                    isLastPage: true
                }
            };
        }

        const methods = await this.findParameterlessMethods(data.uri);

        if (methods.length === 0) {
            return {
                kind: "page",
                page: {
                    title: "Run Script Method",
                    description:
                        "No parameterless methods found in this file. Only methods with no parameters can be executed.",
                    schema: {
                        properties: {}
                    },
                    isLastPage: true
                }
            };
        }

        const metamodelUri = await this.getScriptMetamodelUri(data.uri);
        if (metamodelUri) {
            const compatibleModels = await this.findCompatibleModels(data.uri, metamodelUri);
            if (methods.length === 1) {
                return this.createModelOnlySelectionPage(compatibleModels);
            }
            return this.createCombinedSelectionPage(methods, compatibleModels);
        }

        if (methods.length === 1) {
            return this.createExecutionStart(data.uri, methods[0]);
        }

        return this.createSelectionPage(methods);
    }

    /**
     * Submits the dialog and returns the execution request.
     *
     * @param params The submit parameters with selected method
     * @returns Completion response with execution request
     */
    async submitAction(params: ActionSubmitParams): Promise<ActionSubmitResponse> {
        const data = params.config.data as FileMenuActionData;
        const rawInputs = params.inputs[0];

        if (!isMethodSelectionInputs(rawInputs)) {
            const methods = await this.findParameterlessMethods(data.uri);
            if (methods.length === 1 && typeof rawInputs === "object" && rawInputs !== null) {
                const modelPath = (rawInputs as unknown as MethodAndModelSelectionInputs).modelPath;
                const modelSubmitError = this.checkSubmitValidationErrors(data.uri, modelPath);
                if (modelSubmitError != undefined) {
                    return { kind: "error", message: "Cannot start execution", description: modelSubmitError };
                }
                return this.createExecutionSubmit(data.uri, methods[0], modelPath);
            }
            return {
                kind: "validation",
                errors: [{ path: "/methodName", message: "Invalid input structure" }]
            };
        }

        const methodName = rawInputs.methodName;

        if (!methodName) {
            return {
                kind: "validation",
                errors: [{ path: "/methodName", message: "Please select a method to execute" }]
            };
        }

        const modelPath = (rawInputs as MethodAndModelSelectionInputs).modelPath;

        const submitValidationError = this.checkSubmitValidationErrors(data.uri, modelPath);
        if (submitValidationError != undefined) {
            return { kind: "error", message: "Cannot start execution", description: submitValidationError };
        }

        return this.createExecutionSubmit(data.uri, methodName, modelPath);
    }

    /**
     * Checks if the given URI string and its transitive dependencies have validation errors.
     *
     * @param mainUri The main document URI string to check along with its transitive deps
     * @returns An error message listing files with errors, or undefined if none found
     */
    private checkValidationErrors(mainUri: string): string | undefined {
        const builder = this.sharedServices.workspace.DocumentBuilder;
        const allUris = new Set<string>([mainUri]);
        for (const dep of builder.getTransitiveDependencies(mainUri)) {
            allUris.add(dep);
        }
        const langiumDocuments = this.sharedServices.workspace.LangiumDocuments;
        return describeValidationErrors(
            [...allUris].flatMap((uri) => langiumDocuments.getDocument(URI.parse(uri)) ?? [])
        );
    }

    /**
     * Checks validation errors for submit, including the optional model file.
     *
     * @param scriptUri URI string of the script file
     * @param modelPath Optional model path from the selection (workspace-relative)
     * @returns An error message or undefined if no errors
     */
    private checkSubmitValidationErrors(scriptUri: string, modelPath: string | undefined): string | undefined {
        const scriptUriStr = URI.parse(scriptUri).toString();
        const urisToCheck = [scriptUriStr];
        if (modelPath != undefined) {
            const modelDoc = this.sharedServices.workspace.LangiumDocuments.all.find(
                (doc) => doc.uri.path === modelPath || doc.uri.path.endsWith(modelPath)
            );
            if (modelDoc != undefined) {
                urisToCheck.push(modelDoc.uri.toString());
            }
        }
        const builder = this.sharedServices.workspace.DocumentBuilder;
        const allUris = new Set<string>();
        for (const uri of urisToCheck) {
            allUris.add(uri);
            for (const dep of builder.getTransitiveDependencies(uri)) {
                allUris.add(dep);
            }
        }
        const langiumDocuments = this.sharedServices.workspace.LangiumDocuments;
        return describeValidationErrors(
            [...allUris].flatMap((uri) => langiumDocuments.getDocument(URI.parse(uri)) ?? [])
        );
    }

    /**
     * Finds all parameterless methods in the script file.
     *
     * @returns Array of method names that have no parameters
     */
    private async findParameterlessMethods(uri: string): Promise<string[]> {
        const parsedUri = URI.parse(uri);
        const document = this.sharedServices.workspace.LangiumDocuments.getDocument(parsedUri);

        if (!document || document.parseResult.lexerErrors.length > 0 || document.parseResult.parserErrors.length > 0) {
            return [];
        }

        const root = document.parseResult.value as ScriptType;

        const parameterlessFunctions = root.functions.filter((func) => {
            return func.parameterList == undefined || func.parameterList.parameters.length === 0;
        });

        return parameterlessFunctions.map((func) => func.name);
    }

    /**
     * Gets the metamodel URI referenced by a script file.
     *
     * @param uri URI of the script file
     * @returns Absolute URI of the metamodel, or undefined if not found
     */
    private async getScriptMetamodelUri(uri: string): Promise<string | undefined> {
        const parsedUri = URI.parse(uri);
        const document = this.sharedServices.workspace.LangiumDocuments.getDocument(parsedUri);

        if (!document || document.parseResult.lexerErrors.length > 0 || document.parseResult.parserErrors.length > 0) {
            return undefined;
        }

        const root = document.parseResult.value as ScriptType;
        const metamodelImport = root.metamodelImport?.file;

        if (!metamodelImport) {
            return undefined;
        }

        return resolveRelativePath(document, metamodelImport).toString();
    }

    /**
     * Gets the metamodel URI from a model file.
     *
     * @param modelUri URI of the model file
     * @returns Absolute URI of the metamodel, or undefined if not found
     */
    private getModelMetamodelUri(modelUri: string): string | undefined {
        const parsedUri = URI.parse(modelUri);
        const document = this.sharedServices.workspace.LangiumDocuments.getDocument(parsedUri);

        if (!document || document.parseResult.lexerErrors.length > 0 || document.parseResult.parserErrors.length > 0) {
            return undefined;
        }

        const root = document.parseResult.value as ModelType;
        const metamodelImport = root.import?.file;

        if (!metamodelImport) {
            return undefined;
        }

        return resolveRelativePath(document, metamodelImport).toString();
    }

    /**
     * Finds all model files that reference the same metamodel as the script.
     *
     * @param scriptUri URI of the script file
     * @param metamodelUri Absolute URI of the metamodel
     * @returns Array of relative paths to compatible model files
     */
    private async findCompatibleModels(scriptUri: string, metamodelUri: string): Promise<string[]> {
        const langiumDocuments = this.sharedServices.workspace.LangiumDocuments;
        const documents = langiumDocuments.all.toArray();
        const modelDocuments = documents.filter((doc) => doc.textDocument.languageId === "model");
        const compatibleModels: string[] = [];

        const metamodelDoc = langiumDocuments.getDocument(URI.parse(metamodelUri));
        if (!metamodelDoc) {
            return compatibleModels;
        }

        for (const doc of modelDocuments) {
            const modelMetamodelUri = this.getModelMetamodelUri(doc.uri.toString());
            if (!modelMetamodelUri) continue;

            const modelMetamodelDoc = langiumDocuments.getDocument(URI.parse(modelMetamodelUri));
            if (!modelMetamodelDoc) continue;

            if (isMetamodelCompatible(modelMetamodelDoc, metamodelDoc, langiumDocuments)) {
                compatibleModels.push(doc.uri.path);
            }
        }

        return compatibleModels;
    }

    /**
     * Creates the method selection dialog page.
     *
     * @param methods The list of available methods
     * @returns The dialog page response
     */
    private createSelectionPage(methods: string[]): ActionStartResponse {
        const schema: ActionSchema = {
            properties: {
                methodName: {
                    enum: methods,
                    placeholder: "Select a method to execute"
                }
            },
            propertyLabels: {
                methodName: "Method"
            }
        };

        return {
            kind: "page",
            page: {
                title: "Run Script Method",
                description: "Select a method to execute. Only methods with no parameters are shown.",
                schema,
                isLastPage: true,
                submitButtonLabel: "Run"
            }
        };
    }

    private createModelOnlySelectionPage(modelAbsolutePaths: string[]): ActionStartResponse {
        const { nodes, rootPath } = buildFileSelectTree(modelAbsolutePaths);
        const schema: ActionSchema = {
            properties: {
                modelPath: {
                    fileSelect: nodes,
                    rootPath,
                    placeholder: modelAbsolutePaths.length > 0 ? "Select a model file" : "No compatible models found"
                }
            },
            propertyLabels: {
                modelPath: "Model File"
            }
        };

        return {
            kind: "page",
            page: {
                title: "Run Script Method",
                description: "Select a model file to execute.",
                schema,
                isLastPage: true,
                submitButtonLabel: "Run"
            }
        };
    }

    /**
     * Creates a combined method + model selection dialog page.
     *
     * @param methods The list of available parameterless methods
     * @param models The list of compatible model file paths
     * @returns The dialog page response
     */
    private createCombinedSelectionPage(methods: string[], modelAbsolutePaths: string[]): ActionStartResponse {
        const { nodes, rootPath } = buildFileSelectTree(modelAbsolutePaths);
        const schema: ActionSchema = {
            properties: {
                methodName: {
                    enum: methods,
                    placeholder: "Select a method to execute"
                },
                modelPath: {
                    fileSelect: nodes,
                    rootPath,
                    placeholder: modelAbsolutePaths.length > 0 ? "Select a model file" : "No compatible models found"
                }
            },
            propertyLabels: {
                methodName: "Method",
                modelPath: "Model File"
            }
        };

        return {
            kind: "page",
            page: {
                title: "Run Script Method",
                description: "Select a method and model file to execute.",
                schema,
                isLastPage: true,
                submitButtonLabel: "Run"
            }
        };
    }

    /**
     * Creates an execution request for the given method name.
     *
     * @param uri The URI of the script file
     * @param methodName The name of the method to execute
     * @param modelPath Optional relative path to the model file
     * @returns The execution request
     */
    private createExecutionRequest(
        uri: string,
        methodName: string,
        absoluteModelPath?: string
    ): ActionExecutionRequest {
        const parsedUri = parseUri(URI.parse(uri));
        if (parsedUri.category !== FileCategory.RegularFile) {
            throw new Error("Invalid file category for script execution");
        }
        const filePath = parsedUri.path;

        return {
            filePath,
            data: {
                filePath,
                methodName,
                ...(absoluteModelPath !== undefined ? { modelPath: absoluteModelPath } : {})
            }
        };
    }

    /**
     * Creates an execution completion response for startAction.
     *
     * @param uri The URI of the script file
     * @param methodName The name of the method to execute
     * @returns The start completion response with execution request
     */
    private createExecutionStart(uri: string, methodName: string): ActionStartResponse {
        const executionRequest = this.createExecutionRequest(uri, methodName);

        if (!executionRequest) {
            return {
                kind: "page",
                page: {
                    title: "Run Script Method",
                    description: "File URI not available",
                    schema: {
                        properties: {}
                    },
                    isLastPage: true
                }
            };
        }

        return {
            kind: "completion",
            executions: [executionRequest]
        };
    }

    /**
     * Creates an execution completion response for submitAction.
     *
     * @param uri The URI of the script file
     * @param methodName The name of the method to execute
     * @param modelPath Optional relative path to the model file
     * @returns The submit completion response with execution request
     */
    private createExecutionSubmit(uri: string, methodName: string, modelPath?: string): ActionSubmitResponse {
        const executionRequest = this.createExecutionRequest(uri, methodName, modelPath);

        if (!executionRequest) {
            return {
                kind: "validation",
                errors: [{ path: "/", message: "File URI not available" }]
            };
        }

        return {
            kind: "completion",
            executions: [executionRequest]
        };
    }
}
