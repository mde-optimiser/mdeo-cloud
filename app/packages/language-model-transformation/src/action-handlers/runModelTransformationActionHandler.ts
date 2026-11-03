import {
    type ActionStartParams,
    type ActionStartResponse,
    type ActionSubmitParams,
    type ActionSubmitResponse,
    type ActionSchema,
    type ActionSchemaFileSelectNode,
    type ActionExecutionRequest,
    type FileMenuActionData,
    parseUri,
    FileCategory,
    describeValidationErrors
} from "@mdeo/language-common";
import { sharedImport, type ActionHandler, resolveRelativePath, buildFileSelectTree } from "@mdeo/language-shared";
import { isMetamodelCompatible } from "@mdeo/language-metamodel";
import type { ExtendedLangiumSharedServices } from "@mdeo/language-common";
import type { ModelTransformationType } from "../grammar/modelTransformationTypes.js";
import { requiredInputs } from "../features/transformationDeclarations.js";
import type { ModelType } from "@mdeo/language-model";

const { URI, UriUtils } = sharedImport("langium");

/**
 * Input data collected from the model selection dialog.
 */
interface ModelSelectionInputs {
    /**
     * The selected model file path (relative to the transformation file).
     */
    modelPath: string;
}

/**
 * Type guard to check if a value is ModelSelectionInputs.
 *
 * @param value The value to check
 * @returns True if the value is ModelSelectionInputs
 */
function isModelSelectionInputs(value: unknown): value is ModelSelectionInputs {
    return typeof value === "object" && value !== null && "modelPath" in value;
}

/**
 * Handler for the "run" action in the Model Transformation language.
 *
 * This handler:
 *  - Finds all model (.model) files in the workspace
 *  - Filters to only show models that reference the same metamodel as the transformation
 *  - Presents them as a dropdown selection
 *  - When selected, creates an execution request with the model path
 */
export class RunModelTransformationActionHandler implements ActionHandler {
    /**
     * Shared Langium services for accessing workspace documents.
     */
    private readonly sharedServices: ExtendedLangiumSharedServices;

    /**
     * Creates a new run model transformation action handler.
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
     *  - Parses the transformation file to get the metamodel reference
     *  - Finds all model files that reference the same metamodel
     *  - Presents those models in a dropdown
     *
     * @param params The action start parameters with file URI data
     * @returns The dialog page with model selection or immediate completion
     */
    async startAction(params: ActionStartParams): Promise<ActionStartResponse> {
        const data = params.data as FileMenuActionData;

        const validationError = this.checkValidationErrors(URI.parse(data.uri).toString());
        if (validationError != undefined) {
            return this.createErrorPage(validationError);
        }

        const requiredInputsError = this.checkRequiredInputs(data.uri);
        if (requiredInputsError != undefined) {
            return this.createErrorPage(requiredInputsError);
        }

        const metamodelUri = await this.getTransformationMetamodelUri(data.uri);
        if (!metamodelUri) {
            return this.createErrorPage("Could not determine metamodel from transformation file.");
        }

        const compatibleModels = await this.findCompatibleModels(data.uri, metamodelUri);

        if (compatibleModels.length === 0) {
            return this.createErrorPage(
                "No compatible model files found. " +
                    "Only models that reference the same metamodel as the transformation can be selected."
            );
        }

        const { nodes, rootPath } = buildFileSelectTree(compatibleModels);
        return this.createSelectionPage(nodes, rootPath);
    }

    /**
     * Submits the dialog and returns the execution request.
     *
     * @param params The submit parameters with selected model
     * @returns Completion response with execution request
     */
    async submitAction(params: ActionSubmitParams): Promise<ActionSubmitResponse> {
        const data = params.config.data as FileMenuActionData;
        const rawInputs = params.inputs[0];

        if (!isModelSelectionInputs(rawInputs)) {
            return {
                kind: "validation",
                errors: [{ path: "/modelPath", message: "Invalid input structure" }]
            };
        }

        const modelPath = rawInputs.modelPath;

        if (!modelPath) {
            return {
                kind: "validation",
                errors: [{ path: "/modelPath", message: "Please select a model file to transform" }]
            };
        }

        const modelDoc = this.sharedServices.workspace.LangiumDocuments.all.find(
            (doc) => doc.uri.path === modelPath || doc.uri.path.endsWith(modelPath)
        );
        if (modelDoc != undefined) {
            const modelValidationError = this.checkValidationErrors(modelDoc.uri.toString());
            if (modelValidationError != undefined) {
                return {
                    kind: "error",
                    message: "Cannot start execution",
                    description: modelValidationError
                };
            }
        }

        return this.createExecutionSubmit(data.uri, modelPath);
    }

    /**
     * Checks if the given URI and its transitive dependencies have validation errors.
     *
     * @param mainUri The main document URI to check (additional URIs are also checked)
     * @param additionalUris Additional URIs to check along with their deps
     * @returns An error message listing files with errors, or undefined if none found
     */
    private checkValidationErrors(mainUri: string, ...additionalUris: string[]): string | undefined {
        const builder = this.sharedServices.workspace.DocumentBuilder;
        const allUris = new Set<string>();
        for (const uri of [mainUri, ...additionalUris]) {
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
     * Gets the metamodel URI from the transformation file.
     *
     * @param uri URI of the transformation file
     * @returns Absolute URI of the metamodel, or undefined if not found
     */
    /**
     * Checks that the transformation can run on its own: a transformation with required inputs
     * only runs when another transformation calls it with arguments.
     *
     * @param uri The transformation's URI
     * @returns A message explaining why it cannot run, or undefined
     */
    private checkRequiredInputs(uri: string): string | undefined {
        const document = this.sharedServices.workspace.LangiumDocuments.getDocument(URI.parse(uri));
        const root = document?.parseResult.value as ModelTransformationType | undefined;
        const required = requiredInputs(root);
        if (required.length === 0) {
            return undefined;
        }
        const names = required.map((p) => `'${p.name}'`).join(", ");
        const fileName = UriUtils.basename(URI.parse(uri));
        return (
            `'${fileName}' has required input${required.length > 1 ? "s" : ""} ${names}, so it cannot run on its own. ` +
            `Import it into another transformation and call it there ('import "./${fileName}" as name', then 'call name(…)'), or give ${required.length > 1 ? "these inputs defaults" : "the input a default"}.`
        );
    }

    private async getTransformationMetamodelUri(uri: string): Promise<string | undefined> {
        const parsedUri = URI.parse(uri);
        const document = this.sharedServices.workspace.LangiumDocuments.getDocument(parsedUri);

        if (!document || document.parseResult.lexerErrors.length > 0 || document.parseResult.parserErrors.length > 0) {
            return undefined;
        }

        const root = document.parseResult.value as ModelTransformationType;
        const metamodelImport = root.import?.file;

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
     * Finds all model files that reference the same metamodel as the transformation.
     *
     * @param transformationUri URI of the transformation file
     * @param metamodelUri Absolute URI of the metamodel
     * @returns Array of relative paths to compatible model files
     */
    private async findCompatibleModels(transformationUri: string, metamodelUri: string): Promise<string[]> {
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
     * Creates the model selection dialog page.
     *
     * @param models The list of available compatible model files
     * @returns The dialog page response
     */
    private createSelectionPage(tree: ActionSchemaFileSelectNode[], rootPath: string): ActionStartResponse {
        const schema: ActionSchema = {
            properties: {
                modelPath: {
                    fileSelect: tree,
                    rootPath,
                    placeholder: "Select a model file to transform"
                }
            },
            propertyLabels: {
                modelPath: "Model File"
            }
        };

        return {
            kind: "page",
            page: {
                title: "Run Model Transformation",
                description: "Select a model file to apply the transformation to.",
                schema,
                isLastPage: true,
                submitButtonLabel: "Run"
            }
        };
    }

    /**
     * Creates an error page response.
     *
     * @param message The error message to display
     * @returns The error page response
     */
    private createErrorPage(message: string): ActionStartResponse {
        return {
            kind: "page",
            page: {
                title: "Run Model Transformation",
                description: message,
                schema: {
                    properties: {}
                },
                isLastPage: true
            }
        };
    }

    /**
     * Creates an execution request for the given model.
     *
     * @param transformationUri The URI of the transformation file
     * @param modelPath The relative path to the model file
     * @returns The execution request
     */
    private createExecutionRequest(transformationUri: string, absoluteModelPath: string): ActionExecutionRequest {
        const parsedUri = parseUri(URI.parse(transformationUri));
        if (parsedUri.category !== FileCategory.RegularFile) {
            throw new Error("Invalid file category for transformation execution");
        }
        const filePath = parsedUri.path;

        return {
            filePath,
            data: {
                filePath,
                modelPath: absoluteModelPath
            }
        };
    }

    /**
     * Creates an execution completion response for submitAction.
     *
     * @param transformationUri The URI of the transformation file
     * @param modelPath The absolute path to the model file
     * @returns The submit completion response with execution request
     */
    private createExecutionSubmit(transformationUri: string, modelPath: string): ActionSubmitResponse {
        try {
            const executionRequest = this.createExecutionRequest(transformationUri, modelPath);
            return {
                kind: "completion",
                executions: [executionRequest]
            };
        } catch {
            return {
                kind: "validation",
                errors: [{ path: "/", message: "Failed to create execution request" }]
            };
        }
    }
}
