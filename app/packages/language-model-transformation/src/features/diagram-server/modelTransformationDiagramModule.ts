import type {
    BindingTarget,
    DiagramConfiguration,
    GModelFactory,
    InstanceMultiBinding,
    LabelEditValidator,
    OperationHandlerConstructor,
    ToolPaletteItemProvider,
    ActionHandlerConstructor
} from "@eclipse-glsp/server";
import type { BaseLayoutEngine, MetadataManager, ModelIdProvider } from "@mdeo/language-shared";
import { BaseDiagramModule, sharedImport } from "@mdeo/language-shared";
import { ModelTransformationGModelFactory } from "./modelTransformationGModelFactory.js";
import { ModelTransformationDiagramConfiguration } from "./modelTransformationDiagramConfiguration.js";
import { ModelTransformationModelIdProvider } from "./modelTransformationModelIdProvider.js";
import { ModelTransformationMetadataManager } from "./modelTransformationMetadataManager.js";
import { ModelTransformationLayoutEngine } from "./modelTransformationLayoutEngine.js";
import { ModelTransformationCreateEdgeSchemaResolver } from "./modelTransformationCreateEdgeSchemaResolver.js";
import { CreatePatternInstanceOperationHandler } from "./handler/createPatternInstanceOperationHandler.js";
import { CreatePatternLinkOperationHandler } from "./handler/createPatternLinkOperationHandler.js";
import { ModelTransformationReconnectEdgeOperationHandler } from "./handler/modelTransformationReconnectEdgeOperationHandler.js";
import { ModelTransformationDeleteElementOperationHandler } from "./handler/modelTransformationDeleteElementOperationHandler.js";
import { ModelTransformationApplyLabelEditOperationHandler } from "./handler/modelTransformationApplyLabelEditOperationHandler.js";
import { AddPropertyValueComparisonOperationHandler } from "./handler/addPropertyValueComparisonOperationHandler.js";
import { AddWhereClauseOperationHandler } from "./handler/addWhereClauseOperationHandler.js";
import { AddVariableOperationHandler } from "./handler/addVariableOperationHandler.js";
import { ChangeLinkTypeOperationHandler } from "./handler/changeLinkTypeOperationHandler.js";
import { ChangePatternElementModifierOperationHandler } from "./handler/changePatternElementModifierOperationHandler.js";
import { InsertControlFlowStatementOperationHandler } from "./handler/insertControlFlowStatementOperationHandler.js";
import { ConvertMatchNodeOperationHandler } from "./handler/convertMatchNodeOperationHandler.js";
import { MovePatternElementToConditionOperationHandler } from "./handler/movePatternElementToConditionOperationHandler.js";
import { ModelTransformationRequestClipboardDataActionHandler } from "./handler/modelTransformationRequestClipboardDataActionHandler.js";
import { ModelTransformationPasteOperationHandler } from "./handler/modelTransformationPasteOperationHandler.js";
import { ModelTransformationLabelEditValidator } from "./modelTransformationLabelEditValidator.js";
import { ModelTransformationToolPaletteItemProvider } from "./modelTransformationToolPaletteItemProvider.js";
import type { CreateEdgeSchemaResolver } from "@mdeo/language-shared";

const { injectable } = sharedImport("inversify");

/**
 * Diagram module for model transformation visualizations.
 * Configures the GLSP diagram with model-transformation-specific factory and configuration.
 */
@injectable()
export class ModelTransformationDiagramModule extends BaseDiagramModule {
    protected override bindDiagramConfiguration(): BindingTarget<DiagramConfiguration> {
        return ModelTransformationDiagramConfiguration;
    }

    protected override bindGModelFactory(): BindingTarget<GModelFactory> {
        return ModelTransformationGModelFactory;
    }

    protected override bindModelIdProvider(): BindingTarget<ModelIdProvider> {
        return ModelTransformationModelIdProvider;
    }

    protected override bindMetadataManager(): BindingTarget<MetadataManager> {
        return ModelTransformationMetadataManager;
    }

    protected override bindCustomLayoutEngine(): BindingTarget<BaseLayoutEngine> {
        return ModelTransformationLayoutEngine;
    }

    protected override bindCreateEdgeSchemaResolver(): BindingTarget<CreateEdgeSchemaResolver> {
        return ModelTransformationCreateEdgeSchemaResolver;
    }

    protected override configureOperationHandlers(binding: InstanceMultiBinding<OperationHandlerConstructor>): void {
        super.configureOperationHandlers(binding);
        binding.add(CreatePatternInstanceOperationHandler);
        binding.add(CreatePatternLinkOperationHandler);
        binding.add(ModelTransformationReconnectEdgeOperationHandler);
        binding.add(ModelTransformationDeleteElementOperationHandler);
        binding.add(ModelTransformationApplyLabelEditOperationHandler);
        binding.add(AddPropertyValueComparisonOperationHandler);
        binding.add(AddWhereClauseOperationHandler);
        binding.add(AddVariableOperationHandler);
        binding.add(ChangeLinkTypeOperationHandler);
        binding.add(ChangePatternElementModifierOperationHandler);
        binding.add(InsertControlFlowStatementOperationHandler);
        binding.add(ConvertMatchNodeOperationHandler);
        binding.add(MovePatternElementToConditionOperationHandler);
        binding.add(ModelTransformationPasteOperationHandler);
    }

    protected override configureActionHandlers(binding: InstanceMultiBinding<ActionHandlerConstructor>): void {
        super.configureActionHandlers(binding);
        binding.add(ModelTransformationRequestClipboardDataActionHandler);
    }

    protected override bindLabelEditValidator(): BindingTarget<LabelEditValidator> {
        return ModelTransformationLabelEditValidator;
    }

    protected override bindToolPaletteItemProvider(): BindingTarget<ToolPaletteItemProvider> {
        return ModelTransformationToolPaletteItemProvider;
    }
}
