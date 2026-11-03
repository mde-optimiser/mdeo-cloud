import {
    isCustomValueType,
    type ClassType,
    type ClassTypeRef,
    type ExpressionTypirServices,
    type FunctionType,
    type Parameter,
    type Property,
    type ValueType
} from "@mdeo/language-expression";
import type { FunctionParameterType, RecordType } from "../grammar/scriptTypes.js";
import type { ScriptTypirSpecifics } from "../plugin.js";

/**
 * Prefix of the type package of a record a script declares.
 */
export const RECORD_PACKAGE_PREFIX = "record";

/**
 * Name of the method every record has that copies it with some fields changed.
 */
export const RECORD_COPY_METHOD = "with";

/**
 * The type package of a record a script declares.
 *
 * Every record gets a package of its own, so a script only sees the record types it declares or
 * imports, not every record of a file it imports from.
 *
 * @param absolutePath The absolute path of the script declaring the record
 * @param name The record name
 * @returns The package, `record<path>/<name>`
 */
export function getRecordPackage(absolutePath: string, name: string): string {
    return `${RECORD_PACKAGE_PREFIX}${absolutePath}/${name}`;
}

/**
 * The type fields of a record are declared with, as a parameter list.
 *
 * @param fields The fields, in declaration order
 * @param fieldType The type of one field
 * @param allDefaulted Whether every parameter may be left out, as for {@link RECORD_COPY_METHOD}
 * @returns One parameter per field
 */
function fieldParameters(
    fields: { name: string; hasDefault: boolean }[],
    fieldType: (index: number) => ValueType,
    allDefaulted: boolean
): Parameter[] {
    return fields.map((field, index) => ({
        name: field.name,
        get type() {
            return fieldType(index);
        },
        hasDefault: allDefaulted || field.hasDefault
    }));
}

/**
 * Builds the class type of a record.
 *
 * Every field is a mutable property, and {@link RECORD_COPY_METHOD} takes every field as an
 * optional parameter. Field types are resolved when first read rather than now, because they may
 * name records that are not registered yet.
 *
 * @param name The record name
 * @param typePackage The type package of the record
 * @param fields The fields, in declaration order, with whether they have a default value
 * @param fieldType Resolves the type of the field at an index
 * @param languageNode The node declaring the record, if any
 * @param fieldNode Returns the node declaring the field at an index, if any
 * @returns The class type
 */
export function createRecordClassType(
    name: string,
    typePackage: string,
    fields: { name: string; hasDefault: boolean }[],
    fieldType: (index: number) => ValueType,
    languageNode?: unknown,
    fieldNode?: (index: number) => unknown
): ClassType {
    const properties: Record<string, Property> = {};
    fields.forEach((field, index) => {
        properties[field.name] = {
            name: field.name,
            isProperty: true,
            readonly: false,
            get type() {
                return fieldType(index);
            },
            languageNode: fieldNode?.(index)
        };
    });
    return {
        name,
        package: typePackage,
        properties,
        methods: {
            [RECORD_COPY_METHOD]: {
                name: RECORD_COPY_METHOD,
                isProperty: false,
                type: createRecordFunctionType(typePackage, name, fields, fieldType, true),
                languageNode
            }
        },
        superTypes: [{ package: "builtin", type: "Any" }],
        languageNode
    };
}

/**
 * Builds the function type of a record's constructor, or of its {@link RECORD_COPY_METHOD}.
 *
 * @param typePackage The type package of the record
 * @param name The record name
 * @param fields The fields, in declaration order, with whether they have a default value
 * @param fieldType Resolves the type of the field at an index
 * @param allDefaulted Whether every parameter may be left out
 * @returns A function type with one signature, taking the fields and returning the record
 */
export function createRecordFunctionType(
    typePackage: string,
    name: string,
    fields: { name: string; hasDefault: boolean }[],
    fieldType: (index: number) => ValueType,
    allDefaulted: boolean
): FunctionType {
    const returnType: ClassTypeRef = { package: typePackage, type: name, isNullable: false };
    return {
        signatures: {
            "": {
                returnType,
                parameters: fieldParameters(fields, fieldType, allDefaulted)
            }
        }
    };
}

/**
 * Describes the fields of a record a script declares.
 *
 * @param record The record node
 * @param typir The Typir services, to infer field types with
 * @returns The fields, and a resolver for their types
 */
export function describeRecordFields(
    record: RecordType,
    typir: ExpressionTypirServices<ScriptTypirSpecifics>
): {
    fields: { name: string; hasDefault: boolean }[];
    fieldType: (index: number) => ValueType;
    fieldNode: (index: number) => FunctionParameterType;
} {
    const parameters = record.parameterList?.parameters ?? [];
    return {
        fields: parameters.map((parameter) => ({
            name: parameter.name,
            hasDefault: parameter.defaultValue != undefined
        })),
        fieldType: (index) => {
            const type = typir.Inference.inferType(parameters[index]!);
            return isCustomValueType(type) ? type.definition : { package: "builtin", type: "Any", isNullable: true };
        },
        fieldNode: (index) => parameters[index]!
    };
}
