import { sharedImport, type GraphMetadata } from "@mdeo/language-shared";
import type { PartialGeneratedModelTransformation } from "../../../grammar/generatedModelTransformationPartialTypes.js";
import { ModelTransformationMetadataManager } from "../modelTransformationMetadataManager.js";
import { adaptGeneratedModelTransformationRoot } from "./generatedModelTransformationAstAdapter.js";

const { injectable } = sharedImport("inversify");

/**
 * Metadata manager for generated model transformation diagrams.
 */
@injectable()
export class GeneratedModelTransformationMetadataManager extends ModelTransformationMetadataManager {
    protected override extractGraphMetadata(sourceModel: PartialGeneratedModelTransformation): GraphMetadata {
        const adaptedRoot = adaptGeneratedModelTransformationRoot(sourceModel);
        if (adaptedRoot == undefined) {
            return { nodes: {}, edges: {} };
        }
        return super.extractGraphMetadata(adaptedRoot);
    }
}
