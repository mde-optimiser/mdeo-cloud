import type { Doc } from "prettier";
import type { LangiumCoreServices } from "langium";
import type { AstSerializerAdditionalServices, PrintContext } from "@mdeo/language-common";
import { ID, INT, STRING } from "@mdeo/language-common";
import { serializeNewlineSep, sharedImport } from "@mdeo/language-shared";
import {
    PrimitiveType,
    SingleMultiplicity,
    RangeMultiplicity,
    Property,
    Class,
    ClassExtensions,
    AssociationEnd,
    Association,
    MetaModel,
    FileImport,
    Enum,
    EnumEntry,
    EnumTypeReference,
    View,
    ViewEntry,
    type PrimitiveTypeType,
    type SingleMultiplicityType,
    type RangeMultiplicityType,
    type PropertyType,
    type ClassType,
    type ClassExtensionsType,
    type AssociationEndType,
    type AssociationType,
    type MetaModelType,
    type EnumType,
    type EnumEntryType,
    type EnumTypeReferenceType,
    type FileImportType,
    type ViewType,
    type ViewEntryType
} from "../grammar/metamodelTypes.js";

const { doc } = sharedImport("prettier");
const { indent, hardline, group, join } = doc.builders;

/**
 * Registers all metamodel serializers for pretty-printing metamodel AST nodes.
 *
 * @param services The Langium core services with AST serializer
 */
export function registerMetamodelSerializers(services: LangiumCoreServices & AstSerializerAdditionalServices): void {
    const { AstSerializer } = services;

    AstSerializer.registerNodeSerializer(PrimitiveType, (ctx) => printPrimitiveType(ctx));
    AstSerializer.registerNodeSerializer(EnumTypeReference, (ctx) => printEnumTypeReference(ctx));
    AstSerializer.registerNodeSerializer(SingleMultiplicity, (ctx) => printSingleMultiplicity(ctx));
    AstSerializer.registerNodeSerializer(RangeMultiplicity, (ctx) => printRangeMultiplicity(ctx));
    AstSerializer.registerNodeSerializer(Property, (ctx) => printProperty(ctx));
    AstSerializer.registerNodeSerializer(Class, (ctx) => printClass(ctx));
    AstSerializer.registerNodeSerializer(ClassExtensions, (ctx) => printClassExtensions(ctx));
    AstSerializer.registerNodeSerializer(EnumEntry, (ctx) => printEnumEntry(ctx));
    AstSerializer.registerNodeSerializer(Enum, (ctx) => printEnum(ctx));
    AstSerializer.registerNodeSerializer(AssociationEnd, (ctx) => printAssociationEnd(ctx));
    AstSerializer.registerNodeSerializer(Association, (ctx) => printAssociation(ctx));
    AstSerializer.registerNodeSerializer(FileImport, (ctx) => printFileImport(ctx));
    AstSerializer.registerNodeSerializer(ViewEntry, (ctx) => printViewEntry(ctx));
    AstSerializer.registerNodeSerializer(View, (ctx) => printView(ctx));
    AstSerializer.registerNodeSerializer(MetaModel, (ctx) => printMetaModel(ctx));
}

/**
 * Prints a file import statement.
 *
 * @param context The print context
 * @returns The formatted import statement
 */
function printFileImport(context: PrintContext<FileImportType>): Doc {
    const { ctx, printPrimitive, getPrimitive } = context;
    return ["import ", printPrimitive(getPrimitive(ctx, "file"), STRING)];
}

/**
 * Prints a primitive type node.
 *
 * @param context The print context
 * @returns The formatted primitive type
 */
function printPrimitiveType(context: PrintContext<PrimitiveTypeType>): Doc {
    const { ctx, getPrimitive } = context;
    return getPrimitive(ctx, "name").value;
}

/**
 * Prints a single multiplicity node.
 *
 * @param context The print context
 * @returns The formatted single multiplicity
 */
function printSingleMultiplicity(context: PrintContext<SingleMultiplicityType>): Doc {
    const { ctx, printPrimitive, getPrimitive } = context;

    const docs: Doc[] = ["["];
    if (ctx.numericValue != undefined) {
        docs.push(printPrimitive(getPrimitive(ctx, "numericValue"), INT));
    } else {
        docs.push(ctx.value!);
    }
    docs.push("]");
    return docs;
}

/**
 * Prints a range multiplicity node.
 *
 * @param context The print context
 * @returns The formatted range multiplicity
 */
function printRangeMultiplicity(context: PrintContext<RangeMultiplicityType>): Doc {
    const { ctx, printPrimitive, getPrimitive } = context;

    const lower = printPrimitive(getPrimitive(ctx, "lower"), INT);
    const upper = ctx.upper != undefined ? ctx.upper : printPrimitive(getPrimitive(ctx, "upperNumeric"), INT);
    return ["[", lower, "..", upper, "]"];
}

/**
 * Prints a property node.
 *
 * @param context The print context
 * @returns The formatted property
 */
function printProperty(context: PrintContext<PropertyType>): Doc {
    const { ctx, printPrimitive, getPrimitive, path, print } = context;
    const name = printPrimitive(getPrimitive(ctx, "name"), ID);
    const type = path.call(print, "type");
    const docs: Doc[] = [name, ": ", type];

    if (ctx.multiplicity != undefined) {
        docs.push(path.call(print, "multiplicity"));
    }

    return docs;
}

/**
 * Prints a class node.
 *
 * @param context The print context
 * @returns The formatted class
 */
function printClass(context: PrintContext<ClassType>): Doc {
    const { ctx, printPrimitive, getPrimitive, path, print } = context;
    const docs: Doc[] = [];

    if (ctx.isAbstract) {
        docs.push("abstract ");
    }

    docs.push("class ");
    docs.push(printPrimitive(getPrimitive(ctx, "name"), ID));

    if (ctx.extensions != undefined) {
        docs.push(" ");
        docs.push(path.call(print, "extensions"));
    }

    docs.push(" {");

    const content = serializeNewlineSep(context, ["properties"], doc.builders);
    if (content.length > 0) {
        docs.push(indent([hardline, content]), hardline);
    }

    docs.push("}");

    return group(docs);
}

/**
 * Prints a class extensions node.
 *
 * @param context The print context
 * @returns The formatted class extensions
 */
function printClassExtensions(context: PrintContext<ClassExtensionsType>): Doc {
    const { path, printReference } = context;

    const extendsRefs = path.map((extendsDef) => {
        return extendsDef.call((ref) => printReference(ref, ID), "class");
    }, "extensions");

    return ["extends ", join(", ", extendsRefs)];
}

/**
 * Prints an association end node.
 *
 * @param context The print context
 * @returns The formatted association end
 */
function printAssociationEnd(context: PrintContext<AssociationEndType>): Doc {
    const { ctx, path, print, printPrimitive, getPrimitive, printReference } = context;
    const docs: Doc[] = [path.call((ref) => printReference(ref, ID), "class")];

    if (ctx.name != undefined) {
        docs.push(".");
        docs.push(printPrimitive(getPrimitive(ctx, "name"), ID));

        if (ctx.multiplicity != undefined) {
            docs.push(path.call(print, "multiplicity"));
        }
    }

    return docs;
}

/**
 * Prints an association node.
 *
 * @param context The print context
 * @returns The formatted association
 */
function printAssociation(context: PrintContext<AssociationType>): Doc {
    const { ctx, path, print } = context;
    const start = path.call(print, "source");
    const operator = ctx.operator;
    const target = path.call(print, "target");

    return [start, " ", operator, " ", target];
}

/**
 * Prints the root metamodel node.
 *
 * @param context The print context
 * @returns The formatted metamodel
 */
function printMetaModel(context: PrintContext<MetaModelType>): Doc {
    return serializeNewlineSep(context, ["imports", "elements", "views"], doc.builders);
}

/**
 * Prints an enum type reference node.
 *
 * @param context The print context
 * @returns The formatted enum type reference
 */
function printEnumTypeReference(context: PrintContext<EnumTypeReferenceType>): Doc {
    const { path, printReference } = context;
    return path.call((ref) => printReference(ref, ID), "enum");
}

/**
 * Prints an enum entry node.
 *
 * @param context The print context
 * @returns The formatted enum entry
 */
function printEnumEntry(context: PrintContext<EnumEntryType>): Doc {
    const { ctx, printPrimitive, getPrimitive } = context;
    return printPrimitive(getPrimitive(ctx, "name"), ID);
}

/**
 * Prints an enum node.
 *
 * @param context The print context
 * @returns The formatted enum
 */
function printEnum(context: PrintContext<EnumType>): Doc {
    const { ctx, printPrimitive, getPrimitive } = context;
    const docs: Doc[] = [];

    docs.push("enum ");
    docs.push(printPrimitive(getPrimitive(ctx, "name"), ID));
    docs.push(" {");

    const content = serializeNewlineSep(context, ["entries"], doc.builders);
    if (content.length > 0) {
        docs.push(indent([hardline, content]), hardline);
    }

    docs.push("}");

    return group(docs);
}

/**
 * Prints a view entry node.
 *
 * @param context The print context
 * @returns The formatted view entry
 */
function printViewEntry(context: PrintContext<ViewEntryType>): Doc {
    const { path, printReference } = context;
    return path.call((ref) => printReference(ref, ID), "element");
}

/**
 * Prints a view node.
 *
 * @param context The print context
 * @returns The formatted view
 */
function printView(context: PrintContext<ViewType>): Doc {
    const { ctx, printPrimitive, getPrimitive } = context;
    const docs: Doc[] = ["view ", printPrimitive(getPrimitive(ctx, "name"), ID), " {"];

    const content = serializeNewlineSep(context, ["entries"], doc.builders);
    if (content.length > 0) {
        docs.push(indent([hardline, content]), hardline);
    }

    docs.push("}");

    return group(docs);
}
