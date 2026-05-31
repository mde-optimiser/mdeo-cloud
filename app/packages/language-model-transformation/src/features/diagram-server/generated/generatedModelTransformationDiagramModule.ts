import type { BindingTarget, DiagramConfiguration, GModelFactory, ToolPaletteItemProvider } from "@eclipse-glsp/server";
import type { BaseLayoutEngine, MetadataManager, ModelIdProvider } from "@mdeo/language-shared";
import { BaseDiagramModule, sharedImport } from "@mdeo/language-shared";
import { ModelTransformationDiagramConfiguration } from "../modelTransformationDiagramConfiguration.js";
import { ModelTransformationModelIdProvider } from "../modelTransformationModelIdProvider.js";
import { ModelTransformationLayoutEngine } from "../modelTransformationLayoutEngine.js";
import { GeneratedModelTransformationGModelFactory } from "./generatedModelTransformationGModelFactory.js";
import { GeneratedModelTransformationMetadataManager } from "./generatedModelTransformationMetadataManager.js";
import { GeneratedModelTransformationToolPaletteItemProvider } from "./generatedModelTransformationToolPaletteItemProvider.js";
import { GeneratedModelTransformationCreateEdgeSchemaResolver } from "./generatedModelTransformationCreateEdgeSchemaResolver.js";
import type { CreateEdgeSchemaResolver } from "@mdeo/language-shared";

const { injectable } = sharedImport("inversify");

/**
 * Diagram module for generated model transformation visualizations.
 */
@injectable()
export class GeneratedModelTransformationDiagramModule extends BaseDiagramModule {
    protected override bindDiagramConfiguration(): BindingTarget<DiagramConfiguration> {
        return ModelTransformationDiagramConfiguration;
    }

    protected override bindGModelFactory(): BindingTarget<GModelFactory> {
        return GeneratedModelTransformationGModelFactory;
    }

    protected override bindModelIdProvider(): BindingTarget<ModelIdProvider> {
        return ModelTransformationModelIdProvider;
    }

    protected override bindMetadataManager(): BindingTarget<MetadataManager> {
        return GeneratedModelTransformationMetadataManager;
    }

    protected override bindCustomLayoutEngine(): BindingTarget<BaseLayoutEngine> {
        return ModelTransformationLayoutEngine;
    }

    protected override bindToolPaletteItemProvider(): BindingTarget<ToolPaletteItemProvider> {
        return GeneratedModelTransformationToolPaletteItemProvider;
    }

    protected override bindCreateEdgeSchemaResolver(): BindingTarget<CreateEdgeSchemaResolver> {
        return GeneratedModelTransformationCreateEdgeSchemaResolver;
    }
}
