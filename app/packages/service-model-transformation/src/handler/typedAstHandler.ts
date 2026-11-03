import {
    type ModelTransformationServices,
    type ModelTransformationType,
    type TypedAst
} from "@mdeo/language-model-transformation";
import { ModelTransformation } from "@mdeo/language-model-transformation";
import { hasErrors, type FileDataHandler } from "@mdeo/service-common";
import { resolveRelativePath } from "@mdeo/language-shared";
import type { LangiumDocument } from "langium";
import { ModelTransformationTypedAstConverter } from "./modelTransformationTypedAstConverter.js";

/**
 * Key for the typed AST handler.
 */
export const TYPED_AST_HANDLER_KEY = "typed-ast";

/**
 * Handler for computing the typed AST of a model transformation file.
 * Converts the language AST into a typed representation suitable for code generation.
 *
 * The typed ASTs of the transformations the file imports (and of those they import) are bundled
 * into the root's `callees`, so the optimizer and the execution service run a calling
 * transformation from this one document. The imported files are documents of the same instance:
 * the build loads them (as local references) and validates them with the file itself, and their
 * reads are tracked as dependencies of this file's data.
 *
 * @param context The file data context with path, content, and services
 * @returns Promise resolving to the file data result with typed AST
 */
export const typedAstHandler: FileDataHandler<TypedAst | null, ModelTransformationServices> = async (context) => {
    const { instance, fileInfo, serverApi } = context;

    if (fileInfo == undefined) {
        return {
            data: null,
            ...serverApi.getTrackedRequests()
        };
    }

    const document = await instance.buildDocument(fileInfo.uri);

    if (hasErrors(document)) {
        return {
            data: null,
            ...serverApi.getTrackedRequests()
        };
    }

    const transformation = document.parseResult.value;
    const reflection = instance.services.shared.AstReflection;
    if (!reflection.isInstance(transformation, ModelTransformation)) {
        throw new Error("Document root is not a ModelTransformation");
    }

    const convert = (root: ModelTransformationType, doc: LangiumDocument) =>
        new ModelTransformationTypedAstConverter(
            instance.services.typir,
            reflection,
            instance.services.shared.workspace.LangiumDocuments
        ).convertModelTransformation(root, doc);

    const typedRoot = await convert(transformation, document);
    const callees = await collectCallees(document, instance.services, convert);
    if (callees === null) {
        return {
            data: null,
            ...serverApi.getTrackedRequests()
        };
    }
    if (Object.keys(callees).length > 0) {
        typedRoot.callees = callees;
    }

    return {
        data: typedRoot,
        ...serverApi.getTrackedRequests()
    };
};

/**
 * Converts every transformation a document imports, directly or through its imports.
 *
 * @param document The importing document
 * @param services The language services
 * @param convert Converts one transformation (with a converter of its own types table)
 * @returns The typed ASTs by absolute path, or null when an imported file is missing or has errors
 */
async function collectCallees(
    document: LangiumDocument,
    services: ModelTransformationServices & { shared: { workspace: { LangiumDocuments: any } } },
    convert: (root: ModelTransformationType, doc: LangiumDocument) => Promise<TypedAst>
): Promise<Record<string, TypedAst> | null> {
    const documents = services.shared.workspace.LangiumDocuments;
    const callees: Record<string, TypedAst> = {};
    const pending: LangiumDocument[] = [document];
    while (pending.length > 0) {
        const current = pending.shift()!;
        const root = current.parseResult.value as ModelTransformationType;
        for (const fileImport of root.imports ?? []) {
            const uri = resolveRelativePath(current, fileImport.file);
            if (uri.path === document.uri.path || uri.path in callees) {
                continue;
            }
            const imported = documents.getDocument(uri) as LangiumDocument | undefined;
            if (imported == undefined || hasErrors(imported)) {
                return null;
            }
            callees[uri.path] = await convert(imported.parseResult.value as ModelTransformationType, imported);
            pending.push(imported);
        }
    }
    return callees;
}
