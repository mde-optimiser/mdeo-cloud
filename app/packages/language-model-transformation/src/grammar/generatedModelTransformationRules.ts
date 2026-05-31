import { createRule, createTerminal } from "@mdeo/language-common";
import { GeneratedModelTransformation } from "./generatedModelTransformationTypes.js";

/**
 * Terminal that captures the full JSON document as a single token.
 */
export const GENERATED_MODEL_TRANSFORMATION_CONTENT = createTerminal("GENERATED_MODEL_TRANSFORMATION_CONTENT")
    .returns(String)
    .as(/{[\s\S]+}/);

/**
 * Root parser rule for generated model transformation files.
 */
export const GeneratedModelTransformationRule = createRule("GeneratedModelTransformationRule")
    .returns(GeneratedModelTransformation)
    .as(({ set }) => [set("content", GENERATED_MODEL_TRANSFORMATION_CONTENT)]);
