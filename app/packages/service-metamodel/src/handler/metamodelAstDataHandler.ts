import type { AssociationEndType, MetamodelServices } from "@mdeo/language-metamodel";
import {
    Association,
    Class,
    Enum,
    EnumTypeReference,
    MetaModel,
    MetamodelAssociationOperators,
    RangeMultiplicity
} from "@mdeo/language-metamodel";
import type { SingleMultiplicityType, RangeMultiplicityType } from "@mdeo/language-metamodel";
import { hasErrors, type FileDataHandler } from "@mdeo/service-common";
import { resolveRelativePath } from "@mdeo/language-shared";

/**
 * Key for the metamodel AST data handler.
 */
export const METAMODEL_AST_DATA_HANDLER_KEY = "metamodel";

/**
 * A resolved multiplicity with lower and upper bounds.
 * upper is -1 to represent an unbounded (∞) upper bound.
 */
export interface MultiplicityData {
    lower: number;
    upper: number;
}

/**
 * Data for a single enum definition in the metamodel.
 */
export interface EnumData {
    /**
     * The enum name.
     */
    name: string;
    /**
     * List of enum entry names.
     */
    entries: string[];
}

/**
 * Data for a single property of a metamodel class.
 */
export interface PropertyData {
    /**
     * The property name.
     */
    name: string;
    /**
     * The enum name if this property has an enum type.
     * Mutually exclusive with primitiveType.
     */
    enumType?: string;
    /**
     * The primitive type name if this property has a primitive type.
     * Mutually exclusive with enumType.
     */
    primitiveType?: string;
    /**
     * The multiplicity of the property, defaulting to {lower: 1, upper: 1} if not specified.
     */
    multiplicity: MultiplicityData;
}

/**
 * Data for a single metamodel class.
 */
export interface ClassData {
    /**
     * The class name.
     */
    name: string;
    /**
     * Whether the class is abstract.
     */
    isAbstract: boolean;
    /**
     * Names of parent classes this class extends.
     */
    extends: string[];
    /**
     * The properties defined on this class.
     */
    properties: PropertyData[];
}

/**
 * Data for one end of an association.
 */
export interface AssociationEndData {
    /**
     * The name of the class at this end.
     */
    className: string;
    /**
     * Optional role name for this end.
     */
    name?: string;
    /**
     * The multiplicity at this end, defaulting to {lower: 1, upper: 1} if not specified.
     */
    multiplicity: MultiplicityData;
}

/**
 * Data for a metamodel association.
 */
export interface AssociationData {
    source: AssociationEndData;
    operator: string;
    target: AssociationEndData;
}

/**
 * The result produced by the metamodel AST data handler.
 */
export interface MetamodelAstData {
    /**
     * The absolute file-system path of this metamodel file.
     */
    path: string;
    /**
     * All class definitions in the metamodel (enums are excluded).
     */
    classes: ClassData[];
    /**
     * All enum definitions in the metamodel.
     */
    enums: EnumData[];
    /**
     * All association definitions in the metamodel.
     */
    associations: AssociationData[];
    /**
     * Absolute file-system paths of all metamodel files imported by this metamodel.
     * These are resolved relative to the metamodel file's location.
     */
    importedMetamodelPaths: string[];
}

function convertMultiplicity(
    mult: SingleMultiplicityType | RangeMultiplicityType | undefined,
    defaultMultiplicity: MultiplicityData = { lower: 1, upper: 1 }
): MultiplicityData {
    if (mult == undefined) {
        return defaultMultiplicity;
    }

    if (mult.$type === RangeMultiplicity.name) {
        const range = mult as RangeMultiplicityType;
        const upper = range.upper === "*" ? -1 : (range.upperNumeric ?? 0);
        return { lower: range.lower ?? 0, upper };
    }

    const single = mult as SingleMultiplicityType;
    if (single.value === "*") {
        return { lower: 0, upper: -1 };
    }
    if (single.value === "+") {
        return { lower: 1, upper: -1 };
    }
    if (single.value === "?") {
        return { lower: 0, upper: 1 };
    }
    const n = single.numericValue;
    if (n != undefined) {
        return { lower: n, upper: n };
    }
    return { lower: 1, upper: 1 };
}

/**
 * File data handler that extracts structured class and association data from a
 * parsed metamodel document.  The result is suitable for downstream consumers
 * (e.g. optimisation services) that need a typed view of the metamodel without
 * dealing with the full Langium AST.
 */
export const metamodelAstDataHandler: FileDataHandler<MetamodelAstData | null, MetamodelServices> = async (context) => {
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

    const root = document.parseResult?.value;
    const reflection = instance.services.shared.AstReflection;
    if (!reflection.isInstance(root, MetaModel)) {
        throw new Error("Document root is not a MetaModel");
    }

    const importedMetamodelPaths: string[] = (root.imports ?? []).flatMap((importItem) => {
        const relPath = importItem.file;
        if (relPath == undefined) {
            return [];
        }
        return [resolveRelativePath(document, relPath).path];
    });

    const classes: ClassData[] = [];
    const enums: EnumData[] = [];
    const associations: AssociationData[] = [];

    for (const element of root.elements) {
        if (reflection.isInstance(element, Class)) {
            classes.push({
                name: element.name ?? "",
                isAbstract: element.isAbstract ?? false,
                extends: (element.extensions?.extensions ?? []).map((ext) => ext.class?.$refText ?? ""),
                properties: (element.properties ?? []).map((prop) => {
                    const result: PropertyData = {
                        name: prop.name ?? "",
                        multiplicity: convertMultiplicity(prop.multiplicity)
                    };
                    if (reflection.isInstance(prop.type, EnumTypeReference)) {
                        result.enumType = prop.type.enum?.$refText ?? "";
                    } else {
                        result.primitiveType = prop.type?.name ?? "";
                    }
                    return result;
                })
            });
        } else if (reflection.isInstance(element, Enum)) {
            enums.push({
                name: element.name ?? "",
                entries: (element.entries ?? []).map((entry) => entry.name ?? "")
            });
        } else if (reflection.isInstance(element, Association)) {
            const compositionSourceOperators: string[] = [
                MetamodelAssociationOperators.COMPOSITION_SOURCE,
                MetamodelAssociationOperators.COMPOSITION_SOURCE_NAVIGABLE_TARGET
            ];
            const compositionTargetOperators: string[] = [
                MetamodelAssociationOperators.COMPOSITION_TARGET,
                MetamodelAssociationOperators.COMPOSITION_TARGET_NAVIGABLE_SOURCE
            ];
            const operator = element.operator ?? "";
            const sourceIsComposition = compositionSourceOperators.includes(operator);
            const targetIsComposition = compositionTargetOperators.includes(operator);

            const mapEnd = (end: AssociationEndType, isComposition: boolean): AssociationEndData => {
                const defaultMultiplicity: MultiplicityData = isComposition
                    ? { lower: 0, upper: 1 }
                    : { lower: 0, upper: -1 };
                const result: AssociationEndData = {
                    className: end.class?.$refText ?? "",
                    multiplicity: convertMultiplicity(end.multiplicity, defaultMultiplicity)
                };
                if (end.name != undefined) {
                    result.name = end.name;
                }
                return result;
            };

            associations.push({
                source: mapEnd(element.source, targetIsComposition),
                operator,
                target: mapEnd(element.target, sourceIsComposition)
            });
        }
    }

    return {
        data: { path: fileInfo.uri.path, classes, enums, associations, importedMetamodelPaths },
        ...serverApi.getTrackedRequests()
    };
};
