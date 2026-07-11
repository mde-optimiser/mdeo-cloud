import {
    createRule,
    or,
    many,
    optional,
    group,
    ref,
    ID,
    STRING,
    INT,
    FLOAT,
    NEWLINE,
    WS,
    ML_COMMENT,
    SL_COMMENT,
    HIDDEN_NEWLINE,
    type ParserRule
} from "@mdeo/language-common";
import { AssociationEnd, Class, Enum, EnumEntry, Property } from "@mdeo/language-metamodel";
import {
    SimpleValue,
    EnumValue,
    SingleValue,
    ListValue,
    LiteralValue,
    PropertyAssignment,
    ObjectInstance,
    Model,
    MetamodelFileImport,
    LinkEnd,
    Link
} from "./modelTypes.js";

export const BOOLEAN = createRule("BOOLEAN")
    .returns(Boolean)
    .as(() => [or("true", "false")]);

export const SimpleValueRule = createRule("SimpleValueRule")
    .returns(SimpleValue)
    .as(({ set }) => [
        or(set("stringValue", STRING), set("numberValue", FLOAT), set("numberValue", INT), set("booleanValue", BOOLEAN))
    ]);

export const EnumValueRule = createRule("EnumValueRule")
    .returns(EnumValue)
    .as(({ set }) => [set("enumRef", ref(Enum, ID)), ".", set("value", ref(EnumEntry, ID))]);

export const SingleValueRule = createRule("SingleValueRule")
    .returns(SingleValue)
    .as(() => [or(SimpleValueRule, EnumValueRule)]);

export const ListValueRule = createRule("ListValueRule")
    .returns(ListValue)
    .as(({ add }) => [
        "[",
        optional(group(add("values", SingleValueRule), many(",", add("values", SingleValueRule)))),
        "]"
    ]);

export const LiteralValueRule = createRule("LiteralValueRule")
    .returns(LiteralValue)
    .as(() => [or(ListValueRule, SimpleValueRule, EnumValueRule)]);

export const PropertyAssignmentRule = createRule("PropertyAssignmentRule")
    .returns(PropertyAssignment)
    .as(({ set }) => [set("name", ref(Property, ID)), "=", set("value", LiteralValueRule)]);

export const ObjectInstanceRule = createRule("ObjectInstanceRule")
    .returns(ObjectInstance)
    .as(({ set, add }) => [
        set("name", ID),
        ":",
        set("class", ref(Class, ID)),
        "{",
        many(or(add("properties", PropertyAssignmentRule), NEWLINE)),
        "}"
    ]);

export const LinkEndRule = createRule("LinkEndRule")
    .returns(LinkEnd)
    .as(({ set }) => [
        set("object", ref(ObjectInstance, ID)),
        optional(group(".", set("property", ref(AssociationEnd, ID))))
    ]);

export const LinkRule = createRule("LinkRule")
    .returns(Link)
    .as(({ set }) => [set("source", LinkEndRule), "--", set("target", LinkEndRule)]);

export const MetamodelFileImportRule = createRule("MetamodelFileImportRule")
    .returns(MetamodelFileImport)
    .as(({ set }) => ["using", set("file", STRING)]);

export function createModelRule(contributedTopLevelRules: ParserRule<any>[] = []): ParserRule<any> {
    return createRule("ModelRule")
        .returns(Model)
        .as(({ add, set }) => [
            many(NEWLINE),
            set("import", MetamodelFileImportRule),
            many(NEWLINE),
            many(
                or(
                    ...contributedTopLevelRules,
                    add("objects", ObjectInstanceRule),
                    add("links", LinkRule),
                    NEWLINE
                )
            )
        ]);
}

export const ModelRule = createModelRule();

export const ModelTerminals = [WS, HIDDEN_NEWLINE, ML_COMMENT, SL_COMMENT];
