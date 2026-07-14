import { type ModelData, type ModelServices, ModelDataConverter, Model, getWrapperInterfaceName } from "@mdeo/language-model";
import {
    type MetamodelClassInfo,
    type MetamodelPropertyInfo,
    type CsvImportBlockType,
    importCsvEntries
} from "@mdeo/language-model-csv";
import { hasErrors, type FileDataHandler } from "@mdeo/service-common";
import { resolveRelativePath } from "@mdeo/language-shared";

export const MODEL_DATA_HANDLER_KEY = "model-data";

export const modelDataHandler: FileDataHandler<ModelData | null, ModelServices> = async (context) => {
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

    const model = document.parseResult.value;
    const reflection = instance.services.shared.AstReflection;

    if (!reflection.isInstance(model, Model)) {
        throw new Error("Document root is not a Model");
    }

    const csvImport = model.imports?.find((imp) => (imp as { $type?: string }).$type === getWrapperInterfaceName("CSV"));
    const csvImportContent = (csvImport as { content?: CsvImportBlockType } | undefined)?.content;

    if (csvImportContent == undefined) {
        const converter = new ModelDataConverter(reflection);
        const modelData = converter.convertModel(model);
        return {
            data: modelData,
            ...serverApi.getTrackedRequests()
        };
    }

    const metamodelPath = model.import?.file;
    if (!metamodelPath) {
        return { data: null, ...serverApi.getTrackedRequests() };
    }

    const metamodelUri = resolveRelativePath(document, metamodelPath);
    const metamodelDoc = instance.services.shared.workspace.LangiumDocuments.getDocument(metamodelUri);
    if (!metamodelDoc) {
        return { data: null, ...serverApi.getTrackedRequests() };
    }

    const metamodelAst = metamodelDoc.parseResult?.value as any;
    const metamodelClasses: MetamodelClassInfo[] = (metamodelAst?.elements ?? [])
        .filter((el: any) => el.$type === "Class")
        .map((cls: any): MetamodelClassInfo => ({
            name: cls.name,
            properties: (cls.properties ?? []).map((prop: any): MetamodelPropertyInfo => {
                const primitiveType = prop.type?.name;
                const enumRef = prop.type?.enum?.ref;
                const isReference = prop.$type === "AssociationEnd";
                return {
                    name: prop.name,
                    type: isReference ? "reference"
                        : enumRef ? "enum"
                        : (primitiveType ?? "string") as MetamodelPropertyInfo["type"],
                    enumEntries: enumRef ? enumRef.entries?.map((e: any) => e.name) : undefined,
                    isReference,
                    referencedClass: isReference ? prop.class?.ref?.name : undefined
                };
            })
        }));

    const csvEntries = await Promise.all(
        csvImportContent.imports.map(async (entry: any) => {
            const csvPath: string = resolveRelativePath(document, entry.file).path;
            const { content } = await serverApi.readFile(csvPath);
            return {
                className: entry.class?.ref?.name ?? "",
                csvText: content
            };
        })
    );

    const result = importCsvEntries(csvEntries, metamodelClasses);

    const modelData: ModelData = {
        metamodelPath,
        instances: result.instances,
        links: result.links
    };

    return {
        data: modelData,
        ...serverApi.getTrackedRequests()
    };
};
