import type { RequestHandler } from "@mdeo/service-common";
import { resolveRelativePath } from "@mdeo/language-shared";
import {
    buildModelPluginDocument,
    type ModelPluginData,
    type ModelPluginRequestResponse
} from "@mdeo/service-model-common";
import { importCsvEntries, MODEL_CSV_LANGUAGE_KEY } from "@mdeo/language-model-csv";
import type { CsvImportBlockType, ModelCsvServices } from "@mdeo/language-model-csv";

/**
 * Handles import-data requests forwarded by the model service.
 *
 * The model service does not know anything about CSV: it hands over the text of
 * the `import CSV { ... }` block together with a description of the metamodel's
 * classes, and this handler parses that text with the CSV grammar, reads the
 * referenced CSV files, and returns the resulting instances and links.
 *
 * Class names are read from `$refText` rather than a resolved reference, because
 * the metamodel document is not loaded in this service. The model service has
 * already validated those references against the real metamodel before
 * forwarding, so the names are known to be valid here.
 */
export const csvImportRequestHandler: RequestHandler<ModelPluginRequestResponse, ModelCsvServices> = async (
    context
): Promise<ModelPluginRequestResponse> => {
    const result = await buildModelPluginDocument(context, MODEL_CSV_LANGUAGE_KEY);

    if (result.type === "empty") {
        return { data: { instances: [], links: [] }, ...context.serverApi.getTrackedRequests() };
    }
    if (result.type === "error") {
        return { data: null, ...context.serverApi.getTrackedRequests() };
    }

    const { document, imports, requestBody } = result;

    const entries = [];
    for (const importBlock of imports) {
        const content = importBlock.content as CsvImportBlockType;
        for (const entry of content.imports ?? []) {
            const file = entry.file ?? "";
            if (file === "") {
                continue;
            }
            const csvPath = resolveRelativePath(document, file).path;
            const { content: csvText } = await context.serverApi.readFile(csvPath);
            entries.push({
                className: entry.class?.$refText ?? "",
                csvText,
                mappings: (entry.mappings ?? []).map((mapping) => ({
                    csvColumn: mapping.csvColumn,
                    property: mapping.property
                }))
            });
        }
    }

    const { instances, links, warnings } = importCsvEntries(entries, requestBody.metamodelClasses);
    const data: ModelPluginData = { instances, links, warnings };

    return { data, ...context.serverApi.getTrackedRequests() };
};
