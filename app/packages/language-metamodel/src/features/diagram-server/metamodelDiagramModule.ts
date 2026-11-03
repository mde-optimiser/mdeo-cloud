import type {
    InstanceMultiBinding,
    LabelEditValidator,
    OperationHandlerConstructor,
    ToolPaletteItemProvider,
    ActionHandlerConstructor
} from "@eclipse-glsp/server";
import type { BindingTarget, DiagramConfiguration, GModelFactory } from "@eclipse-glsp/server";
import type { BaseLayoutEngine, MetadataManager, ModelIdProvider } from "@mdeo/language-shared";
import { BaseDiagramModule, sharedImport } from "@mdeo/language-shared";
import { MetamodelGModelFactory } from "./metamodelGModelFactory.js";
import { MetamodelDiagramConfiguration } from "./metamodelDiagramConfiguration.js";
import { MetamodelModelIdProvider } from "./metamodelModelIdProvider.js";
import { MetamodelMetadataManager } from "./metamodelMetadataManager.js";
import { MetamodelApplyLabelEditOperationHandler } from "./handler/metamodelApplyLabelEditOperationHandler.js";
import { MetamodelReconnectEdgeOperationHandler } from "./handler/metamodelReconnectEdgeOperationHandler.js";
import { CreateClassOperationHandler } from "./handler/createClassOperationHandler.js";
import { CreateEnumOperationHandler } from "./handler/createEnumOperationHandler.js";
import { MetamodelDeleteNodeOperationHandler } from "./handler/metamodelDeleteElementOperationHandler.js";
import { CreateAssociationOperationHandler } from "./handler/createAssociationOperationHandler.js";
import { CreateInheritanceOperationHandler } from "./handler/createInheritanceOperationHandler.js";
import { MetamodelLabelEditValidator } from "./metamodelLabelEditValidator.js";
import { MetamodelToolPaletteItemProvider } from "./metamodelToolPaletteItemProvider.js";
import { MetamodelLayoutEngine } from "./metamodelLayoutEngine.js";
import { MetamodelCreateEdgeSchemaResolver } from "./metamodelCreateEdgeSchemaResolver.js";
import type { CreateEdgeSchemaResolver } from "@mdeo/language-shared";
import { AddEnumEntryOperationHandler } from "./handler/addEnumEntryOperationHandler.js";
import { AddPropertyOperationHandler } from "./handler/addPropertyOperationHandler.js";
import { ChangeAssociationEndOperationHandler } from "./handler/changeAssociationEndOperationHandler.js";
import { ToggleAbstractClassOperationHandler } from "./handler/toggleAbstractClassOperationHandler.js";
import { MetamodelRequestClipboardDataActionHandler } from "./handler/metamodelRequestClipboardDataActionHandler.js";
import { MetamodelPasteOperationHandler } from "./handler/metamodelPasteOperationHandler.js";
import { MetamodelExistingNamesProvider } from "./metamodelExistingNamesProvider.js";
import type { ExistingNamesProvider } from "@mdeo/language-shared";
import type { BindingContext } from "@eclipse-glsp/server";
import { MetamodelViewState } from "./metamodelViews.js";
import {
    AddToMetamodelViewOperationHandler,
    CreateMetamodelViewOperationHandler,
    DeleteMetamodelViewOperationHandler,
    RemoveFromMetamodelViewOperationHandler,
    RenameMetamodelViewOperationHandler,
    SelectMetamodelViewActionHandler
} from "./handler/metamodelViewOperationHandlers.js";
const { injectable } = sharedImport("inversify");

/**
 * Diagram module for metamodel visualizations.
 * Configures the GLSP diagram with metamodel-specific factory and configuration.
 */
@injectable()
export class MetamodelDiagramModule extends BaseDiagramModule {
    protected override bindDiagramConfiguration(): BindingTarget<DiagramConfiguration> {
        return MetamodelDiagramConfiguration;
    }

    protected override bindGModelFactory(): BindingTarget<GModelFactory> {
        return MetamodelGModelFactory;
    }

    protected override bindModelIdProvider(): BindingTarget<ModelIdProvider> {
        return MetamodelModelIdProvider;
    }

    protected override bindMetadataManager(): BindingTarget<MetadataManager> {
        return MetamodelMetadataManager;
    }

    protected override configureOperationHandlers(binding: InstanceMultiBinding<OperationHandlerConstructor>): void {
        super.configureOperationHandlers(binding);
        binding.add(MetamodelApplyLabelEditOperationHandler);
        binding.add(MetamodelReconnectEdgeOperationHandler);
        binding.add(CreateClassOperationHandler);
        binding.add(CreateEnumOperationHandler);
        binding.add(MetamodelDeleteNodeOperationHandler);
        binding.add(CreateAssociationOperationHandler);
        binding.add(CreateInheritanceOperationHandler);
        binding.add(ToggleAbstractClassOperationHandler);
        binding.add(ChangeAssociationEndOperationHandler);
        binding.add(AddPropertyOperationHandler);
        binding.add(AddEnumEntryOperationHandler);
        binding.add(MetamodelPasteOperationHandler);
        binding.add(AddToMetamodelViewOperationHandler);
        binding.add(RemoveFromMetamodelViewOperationHandler);
        binding.add(CreateMetamodelViewOperationHandler);
        binding.add(RenameMetamodelViewOperationHandler);
        binding.add(DeleteMetamodelViewOperationHandler);
    }

    protected override configureActionHandlers(binding: InstanceMultiBinding<ActionHandlerConstructor>): void {
        super.configureActionHandlers(binding);
        binding.add(MetamodelRequestClipboardDataActionHandler);
        binding.add(SelectMetamodelViewActionHandler);
    }

    protected override configureAdditional(context: BindingContext): void {
        super.configureAdditional(context);
        context.bind(MetamodelViewState).toSelf().inSingletonScope();
    }

    protected override bindLabelEditValidator(): BindingTarget<LabelEditValidator> {
        return MetamodelLabelEditValidator;
    }

    protected override bindToolPaletteItemProvider(): BindingTarget<ToolPaletteItemProvider> {
        return MetamodelToolPaletteItemProvider;
    }

    protected override bindCustomLayoutEngine(): BindingTarget<BaseLayoutEngine> {
        return MetamodelLayoutEngine;
    }

    protected override bindCreateEdgeSchemaResolver(): BindingTarget<CreateEdgeSchemaResolver> {
        return MetamodelCreateEdgeSchemaResolver;
    }

    protected override bindExistingNamesProvider(): BindingTarget<ExistingNamesProvider> {
        return MetamodelExistingNamesProvider;
    }
}
