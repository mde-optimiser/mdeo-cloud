import {
    type GeneratedModelTransformationServices,
    adaptGeneratedModelTransformationText
} from "@mdeo/language-model-transformation";
import { hasErrors, type FileDataHandler } from "@mdeo/service-common";
import type { AstSerializer } from "@mdeo/language-common";
import type { LangiumDocument } from "langium";

/**
 * Key for retrieving generated model transformations as regular .mt source text.
 */
export const GENERATED_MODEL_TRANSFORMATION_TEXT_HANDLER_KEY = "model-transformation-text";

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

/**
 * Converts a generated model transformation (.mt_gen) into regular .mt text.
 */
export const generatedModelTransformationTextHandler: FileDataHandler<string | null, GeneratedModelTransformationServices> =
    async (context) => {
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

        const root = document.parseResult.value as unknown as { content?: unknown };
        const content = typeof root.content === "string" ? root.content : undefined;
        const model = adaptGeneratedModelTransformationText(content);
        if (model == undefined) {
            return {
                data: null,
                ...serverApi.getTrackedRequests()
            };
        }

        const serializer: AstSerializer = instance.services.AstSerializer;
        const serialized = await serializer.serializeNode(model as any, createMinimalDocument("fake:///generated.mt"), {
            insertSpaces: true,
            tabSize: 4
        });

        return {
            data: serialized,
            ...serverApi.getTrackedRequests()
        };
    };
