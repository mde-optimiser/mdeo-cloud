import type {
    ActionDialogPage,
    ActionSchemaFileSelectNode,
    ActionStartParams,
    ActionStartResponse,
    ActionSubmitParams,
    ActionSubmitResponse,
    AstSerializerAdditionalServices,
    FileMenuActionData
} from "@mdeo/language-common";
import { FileCategory, ID, STRING, parseUri } from "@mdeo/language-common";
import { buildFileSelectTree, calculateRelativePath, sharedImport, type ActionHandler } from "@mdeo/language-shared";
import type { LangiumDocument } from "langium";
import type { LangiumServices } from "langium/lsp";
import type { ModelTransformationType } from "../grammar/modelTransformationTypes.js";
import {
    declaredNames,
    headerInsertEdit,
    importAlias,
    unquotedName
} from "../features/diagram-server/modelTransformationHeader.js";

const { URI } = sharedImport("langium");

/**
 * Input data collected from the import dialog.
 */
interface ImportTransformationInputs {
    /**
     * The absolute path of the transformation to import.
     */
    transformation: string;
    /**
     * The alias, empty to derive it from the file name.
     */
    alias?: string;
}

/**
 * Type guard to check if a value is ImportTransformationInputs.
 *
 * @param value The value to check
 * @returns True if the value is ImportTransformationInputs
 */
function isImportTransformationInputs(value: unknown): value is ImportTransformationInputs {
    return (
        typeof value === "object" &&
        value !== null &&
        "transformation" in value &&
        (typeof (value as ImportTransformationInputs).transformation === "string" ||
            (value as ImportTransformationInputs).transformation === undefined)
    );
}

/**
 * Action handler importing another transformation into a transformation file, as triggered by
 * the "Import…" item of the diagram's toolbox.
 *
 * A single dialog page selects the `.mt` file and optionally names the alias; without one, the
 * alias is the file's base name in camel case, made unique. The line
 * `import "./relative/path.mt" as alias` is inserted after the other imports (or after `using`).
 */
export class ImportTransformationActionHandler implements ActionHandler {
    /**
     * Creates the handler.
     *
     * @param services The services of the transformation language
     */
    constructor(private readonly services: LangiumServices & AstSerializerAdditionalServices) {}

    /**
     * Starts the dialog: lists every other transformation of the workspace.
     *
     * @param params The action start parameters, carrying the URI of the importing file
     * @returns The dialog page
     */
    async startAction(params: ActionStartParams): Promise<ActionStartResponse> {
        const data = params.data as FileMenuActionData;
        const currentPath = URI.parse(data.uri).path;
        const files = this.services.shared.workspace.LangiumDocuments.all
            .toArray()
            .filter(
                (document: LangiumDocument) =>
                    document.uri.path.endsWith(".mt") &&
                    document.uri.path !== currentPath &&
                    document.textDocument.languageId === "model-transformation"
            )
            .map((document: LangiumDocument) => document.uri.path);
        if (files.length === 0) {
            return {
                kind: "error",
                message: "There is no other transformation to import",
                description: "Create the transformation to call first; every .mt file can be imported."
            };
        }
        const { nodes, rootPath } = buildFileSelectTree(files);
        return { kind: "page", page: this.createPage(nodes, rootPath) };
    }

    /**
     * Inserts the import, or reports what is wrong with the input.
     *
     * @param params The submit parameters with the collected inputs
     * @returns Validation errors or completion
     */
    async submitAction(params: ActionSubmitParams): Promise<ActionSubmitResponse> {
        const data = params.config.data as FileMenuActionData;
        const inputs = params.inputs[0];
        if (!isImportTransformationInputs(inputs) || !inputs.transformation) {
            return {
                kind: "validation",
                errors: [{ path: "/transformation", message: "Please select a transformation" }]
            };
        }

        const parsedUri = parseUri(URI.parse(data.uri));
        if (parsedUri.category !== FileCategory.RegularFile) {
            throw new Error("ImportTransformationActionHandler can only be invoked on regular files");
        }
        const document = this.services.shared.workspace.LangiumDocuments.all
            .toArray()
            .find((candidate: LangiumDocument) => candidate.uri.toString() === data.uri);
        const root = document?.parseResult.value as ModelTransformationType | undefined;
        if (document == undefined || root == undefined) {
            return { kind: "validation", errors: [{ path: "/transformation", message: "The file is not loaded" }] };
        }

        const relativePath = calculateRelativePath(parsedUri.path, inputs.transformation);
        if ((root.imports ?? []).some((existing) => existing.file === relativePath)) {
            return {
                kind: "validation",
                errors: [{ path: "/transformation", message: `"${relativePath}" is imported already` }]
            };
        }

        const used = declaredNames(root);
        const written = inputs.alias?.trim() ?? "";
        const alias = written.length > 0 ? unquotedName(written) : importAlias(relativePath, used);
        if (used.has(alias)) {
            return {
                kind: "validation",
                errors: [{ path: "/alias", message: `The name '${alias}' is used already in this file` }]
            };
        }

        const serializer = this.services.AstSerializer;
        const line = `import ${serializer.serializePrimitive({ value: relativePath }, STRING)} as ${serializer.serializePrimitive({ value: alias }, ID)}`;
        const edit = headerInsertEdit(root, document, line);
        if (edit == undefined) {
            return {
                kind: "validation",
                errors: [{ path: "/transformation", message: "The file has no 'using' line to import after" }]
            };
        }
        await this.services.shared.lsp.Connection?.workspace.applyEdit({ changes: { [data.uri]: [edit] } });
        return { kind: "completion" };
    }

    /**
     * Creates the dialog page.
     *
     * @param tree The selectable transformations
     * @param rootPath The common path prefix of the tree
     * @returns The page
     */
    private createPage(tree: ActionSchemaFileSelectNode[], rootPath: string): ActionDialogPage {
        return {
            title: "Import Transformation",
            description:
                "Select the transformation to call from this file. It is imported under an alias, by which calls name it.",
            schema: {
                properties: {
                    transformation: {
                        fileSelect: tree,
                        rootPath,
                        placeholder: "Select a transformation"
                    },
                    alias: {
                        type: "string",
                        placeholder: "Derived from the file name"
                    }
                },
                propertyLabels: {
                    transformation: "Transformation",
                    alias: "Alias"
                }
            },
            isLastPage: true,
            submitButtonLabel: "Import"
        };
    }
}
