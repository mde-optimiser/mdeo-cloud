import type {
    ActionDialogPage,
    ActionSchema,
    ActionSchemaFileSelectNode,
    ActionStartParams,
    ActionStartResponse,
    ActionSubmitParams,
    ActionSubmitResponse,
    AstSerializer,
    AstSerializerAdditionalServices,
    FileMenuActionData
} from "@mdeo/language-common";
import { buildFileSelectTree, calculateRelativePath, sharedImport, type ActionHandler } from "@mdeo/language-shared";
import type { LangiumCoreServices, LangiumDocument } from "langium";
import type { LangiumSharedServices } from "langium/lsp";
import type { WorkspaceEdit } from "vscode-languageserver-types";
import { adaptGeneratedModelTransformationText } from "../features/diagram-server/generated/generatedModelTransformationAstAdapter.js";
import type { ModelTransformationType } from "../grammar/modelTransformationTypes.js";

const { URI } = sharedImport("langium");

function createMinimalDocument(uri: string): LangiumDocument {
    return {
        textDocument: {
            getText: () => "",
            positionAt: () => ({ line: 0, character: 0 }),
            uri
        },
        uri: { path: uri, scheme: "fake", authority: "" }
    } as unknown as LangiumDocument;
}

interface SaveAsModelTransformationInputs {
    directory: string | undefined;
    filename: string;
}

function isSaveAsModelTransformationInputs(value: unknown): value is SaveAsModelTransformationInputs {
    return (
        typeof value === "object" &&
        value !== null &&
        "filename" in value &&
        typeof (value as SaveAsModelTransformationInputs).filename === "string" &&
        (!("directory" in value) ||
            (value as SaveAsModelTransformationInputs).directory === undefined ||
            typeof (value as SaveAsModelTransformationInputs).directory === "string")
    );
}

/**
 * Saves generated model transformation files as regular .mt files.
 */
export class SaveGeneratedModelTransformationActionHandler implements ActionHandler {
    private readonly sharedServices: LangiumSharedServices;

    constructor(sharedServices: LangiumSharedServices) {
        this.sharedServices = sharedServices;
    }

    async startAction(params: ActionStartParams): Promise<ActionStartResponse> {
        const data = params.data as FileMenuActionData;
        const generated = this.parseGeneratedModelTransformation(data.uri);
        if (generated == undefined) {
            return {
                kind: "error",
                message: "Failed to parse generated model transformation",
                description: "The generated model transformation file could not be parsed."
            };
        }

        const filePaths = this.findRegularFilePaths();
        if (filePaths.length === 0) {
            return {
                kind: "error",
                message: "No files found",
                description: "No files were found in the workspace to determine directory structure."
            };
        }

        const { nodes, rootPath } = buildFileSelectTree(filePaths);
        return this.createDialogPage(nodes, rootPath);
    }

    async submitAction(params: ActionSubmitParams): Promise<ActionSubmitResponse> {
        const data = params.config.data as FileMenuActionData;
        const rawInputs = params.inputs[0];

        if (!isSaveAsModelTransformationInputs(rawInputs)) {
            return {
                kind: "validation",
                errors: [{ path: "/directory", message: "Invalid input structure" }]
            };
        }

        const { directory, filename } = rawInputs;

        if (!filename || filename.trim().length === 0) {
            return {
                kind: "validation",
                errors: [{ path: "/filename", message: "Please enter a filename" }]
            };
        }

        const normalizedFilename = filename.endsWith(".mt") ? filename : filename + ".mt";

        if (normalizedFilename.includes("/") || normalizedFilename.includes("\\")) {
            return {
                kind: "validation",
                errors: [{ path: "/filename", message: "Filename must not contain path separators" }]
            };
        }

        return this.performSave(data, directory ?? "", normalizedFilename);
    }

    private async performSave(
        data: FileMenuActionData,
        directory: string,
        normalizedFilename: string
    ): Promise<ActionSubmitResponse> {
        const sourceUri = URI.parse(data.uri);
        const projectId = sourceUri.path.substring(1).split("/")[0];
        const newFileUri = URI.file(`/${projectId}/files${directory}/${normalizedFilename}`);

        if (this.sharedServices.workspace.LangiumDocuments.hasDocument(newFileUri)) {
            return {
                kind: "validation",
                errors: [
                    {
                        path: "/filename",
                        message: `A file named "${normalizedFilename}" already exists in this directory`
                    }
                ]
            };
        }

        const generated = this.parseGeneratedModelTransformation(data.uri);
        if (generated == undefined) {
            return {
                kind: "error",
                message: "Failed to parse generated model transformation",
                description: "The generated model transformation file could not be parsed."
            };
        }

        const metamodelAbsolutePath = this.resolveMetamodelPath(generated.metamodelPath);
        if (metamodelAbsolutePath == undefined) {
            return {
                kind: "error",
                message: "Metamodel not found",
                description: `Could not find metamodel "${generated.metamodelPath}" in the workspace.`
            };
        }

        const withinFilesTargetPath = directory + "/" + normalizedFilename;
        const metamodelRelativePath = calculateRelativePath(withinFilesTargetPath, metamodelAbsolutePath);

        // Preserve the generated transformation structure while switching to the user-visible import path.
        const importNode = generated.model.import as unknown as { file: string };
        importNode.file = metamodelRelativePath;

        const fileContent = await this.serializeModelTransformation(generated.model);

        const workspaceEdit = this.createFileWorkspaceEdit(newFileUri.toString(), fileContent);
        const connection = this.sharedServices.lsp.Connection;
        await connection?.workspace.applyEdit(workspaceEdit);

        return { kind: "completion" };
    }

    private parseGeneratedModelTransformation(
        uri: string
    ): { model: ModelTransformationType; metamodelPath: string } | undefined {
        const document = this.sharedServices.workspace.LangiumDocuments.getDocument(URI.parse(uri));
        if (document == undefined) {
            return undefined;
        }

        const root = document.parseResult.value as unknown as Record<string, unknown>;
        const content = root?.content;
        if (typeof content !== "string") {
            return undefined;
        }

        const model = adaptGeneratedModelTransformationText(content);
        if (model == undefined) {
            return undefined;
        }

        const metamodelPath = (model.import as unknown as { file?: string })?.file ?? "";

        return { model, metamodelPath };
    }

    private findRegularFilePaths(): string[] {
        const documents = this.sharedServices.workspace.LangiumDocuments.all.toArray();
        const paths: string[] = [];

        for (const doc of documents) {
            const uriPath = doc.uri.path;
            const parts = uriPath.substring(1).split("/");
            if (parts[1] === "files") {
                paths.push(uriPath);
            }
        }

        return paths;
    }

    private resolveMetamodelPath(metamodelPathFromData: string): string | undefined {
        const targetBasename = metamodelPathFromData.split("/").pop() ?? metamodelPathFromData;

        const metamodelDocs = this.sharedServices.workspace.LangiumDocuments.all
            .filter((doc) => doc.textDocument.languageId === "metamodel")
            .toArray();

        for (const doc of metamodelDocs) {
            const docPath = doc.uri.path;
            const docBasename = docPath.split("/").pop();
            if (docBasename === targetBasename) {
                const parts = docPath.substring(1).split("/");
                if (parts[1] === "files") {
                    return "/" + parts.slice(2).join("/");
                }
                return "/" + parts.slice(1).join("/");
            }
        }

        return undefined;
    }

    private async serializeModelTransformation(modelAst: ModelTransformationType): Promise<string> {
        const fakeUri = URI.parse("fake:///file.mt");
        const services = this.sharedServices.ServiceRegistry.getServices(fakeUri) as LangiumCoreServices &
            AstSerializerAdditionalServices;
        const serializer: AstSerializer = services.AstSerializer;
        const fakeDocument = createMinimalDocument("fake:///document.mt");

        return serializer.serializeNode(modelAst as any, fakeDocument, {
            insertSpaces: true,
            tabSize: 4
        });
    }

    private createDialogPage(tree: ActionSchemaFileSelectNode[], rootPath: string): ActionStartResponse {
        const schema: ActionSchema = {
            properties: {
                directory: {
                    fileSelect: tree,
                    rootPath,
                    selectDirectory: true,
                    placeholder: "Select target directory (defaults to root)"
                },
                filename: {
                    type: "string",
                    placeholder: "e.g. myTransformation.mt"
                }
            },
            propertyLabels: {
                directory: "Directory",
                filename: "Filename"
            }
        };

        const page: ActionDialogPage = {
            title: "Save as Model Transformation",
            description: "Save this generated model transformation as a regular .mt file",
            schema,
            isLastPage: true,
            submitButtonLabel: "Save"
        };

        return { kind: "page", page };
    }

    private createFileWorkspaceEdit(uri: string, content: string): WorkspaceEdit {
        return {
            documentChanges: [
                {
                    kind: "create" as const,
                    uri,
                    options: { overwrite: false, ignoreIfExists: false }
                },
                {
                    textDocument: { uri, version: null },
                    edits: [
                        {
                            range: {
                                start: { line: 0, character: 0 },
                                end: { line: 0, character: 0 }
                            },
                            newText: content
                        }
                    ]
                }
            ]
        };
    }
}
