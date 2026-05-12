import type {
    BindingContext,
    BindingTarget,
    ContextActionsProvider,
    ContextEditValidatorRegistry,
    InstanceMultiBinding,
    MultiBinding,
    OperationHandlerConstructor,
    ActionHandlerConstructor,
    ModelValidator as ModelValidatorType
} from "@eclipse-glsp/server";
import { sharedImport } from "../sharedImport.js";
import { ModelState } from "./modelState.js";
import { GModelIndex } from "./modelIndex.js";
import { SourceModelStorage } from "./sourceModelStorage.js";
import type { LanguageServices } from "@mdeo/language-common";
import type { interfaces } from "inversify";
import { AstReflectionKey, GedWorkerBaseUrl, LanguageServicesKey } from "./langiumServices.js";
import { ModelIdProvider } from "./modelIdProvider.js";
import { ChangeBoundsOperationHandler } from "./handler/changeBoundsOperationHandler.js";
import { PartialChangeBoundsOperationHandler } from "./handler/partialChangeBoundsOperationHandler.js";
import { TriggerActionOperationHandler } from "./handler/triggerActionOperationHandler.js";
import { UpdateRoutingInformationOperationHandler } from "./handler/updateRoutingInformationOperationHandler.js";
import { MetadataManager } from "./metadataManager.js";
import { UpdateClientOperationHandler } from "./handler/updateClientHandler.js";
import { ExtendedContextEditValidatorRegistry } from "./contextEditValidationRegistry.js";
import { ModelSubmissionHandler } from "./modelSubmissionHandler.js";
import { BaseLayoutEngine } from "./layoutEngine.js";
import { LayoutOperationHandler } from "./handler/layoutOperationHandler.js";
import { ResetLayoutOperationHandler } from "./handler/resetLayoutOperationHandler.js";
import { RequestCreateEdgeSchemaActionHandler } from "./handler/requestCreateEdgeSchemaActionHandler.js";
import { RevealSourceActionHandler } from "./handler/revealSourceActionHandler.js";
import { CreateEdgeSchemaResolver } from "./createEdgeSchemaResolver.js";
import { DefaultContextActionsProvider } from "../context-actions/defaultContextActionsProvider.js";
import { LangiumModelValidator } from "./langiumModelValidator.js";
import { UpdateEditorSettingsActionHandler } from "./handler/updateEditorSettingsActionHandler.js";
import { EditorSettingsService } from "./editorSettingsService.js";
import { BaseCutOperationHandler } from "./handler/baseCutOperationHandler.js";
import { ExistingNamesProvider, DefaultExistingNamesProvider } from "./existingNamesProvider.js";
import type { ExistingNamesProvider as IExistingNamesProvider } from "./existingNamesProvider.js";

const { injectable } = sharedImport("inversify");
const { DiagramModule, bindOrRebind, applyBindingTarget, CompoundOperationHandler } =
    sharedImport("@eclipse-glsp/server");

/**
 * Abstract base class for diagram modules that integrate Langium services.
 * Extends the GLSP DiagramModule with bindings for Langium language services.
 */
@injectable()
export abstract class BaseDiagramModule extends DiagramModule {
    override get diagramType() {
        return this.services.LanguageMetaData.languageId;
    }

    /**
     * Creates a new diagram module with the given language services.
     *
     * @param services The language services to bind into the GLSP container
     * @param languageJsUrl The HTTP URL of the plugin's `language.js` entry file,
     *                      used to resolve the GED worker script URL at runtime.
     *                      When provided, `GedWorkerBaseUrl` is bound in the container
     *                      so that the metadata manager can derive the correct worker path.
     */
    constructor(
        protected readonly services: LanguageServices,
        protected readonly languageJsUrl?: string
    ) {
        super();
    }

    /**
     * Extends the base GLSP container configuration with Langium-specific bindings:
     * language services, AST reflection, model ID provider, metadata manager,
     * layout engine, create-edge schema resolver, and any additional bindings
     * supplied by {@link configureAdditional}.
     *
     * @param bind   Inversify `bind` function.
     * @param unbind Inversify `unbind` function.
     * @param isBound Inversify `isBound` function.
     * @param rebind Inversify `rebind` function.
     */
    protected override configure(
        bind: interfaces.Bind,
        unbind: interfaces.Unbind,
        isBound: interfaces.IsBound,
        rebind: interfaces.Rebind
    ): void {
        super.configure(bind, unbind, isBound, rebind);
        const context = { bind, unbind, isBound, rebind };
        bindOrRebind(context, LanguageServicesKey).toConstantValue(this.services);
        bindOrRebind(context, AstReflectionKey).toConstantValue(this.services.shared.AstReflection);
        if (this.languageJsUrl !== undefined) {
            bindOrRebind(context, GedWorkerBaseUrl).toConstantValue(this.languageJsUrl);
        }
        applyBindingTarget(context, ModelIdProvider, this.bindModelIdProvider()).inSingletonScope();
        applyBindingTarget(context, MetadataManager, this.bindMetadataManager()).inSingletonScope();
        applyBindingTarget(context, BaseLayoutEngine, this.bindCustomLayoutEngine()).inSingletonScope();
        applyBindingTarget(context, CreateEdgeSchemaResolver, this.bindCreateEdgeSchemaResolver()).inSingletonScope();
        applyBindingTarget(context, ExistingNamesProvider, this.bindExistingNamesProvider()).inSingletonScope();
        bind(EditorSettingsService).toSelf().inSingletonScope();
        this.configureAdditional(context);
    }

    /**
     * Registers {@link DefaultContextActionsProvider} in addition to the providers
     * configured by the base class.
     *
     * @param binding The multi-binding used to register context-action providers.
     */
    protected override configureContextActionProviders(binding: MultiBinding<ContextActionsProvider>): void {
        super.configureContextActionProviders(binding);
        binding.add(DefaultContextActionsProvider);
    }

    /**
     * Registers {@link RequestCreateEdgeSchemaActionHandler} and
     * {@link UpdateEditorSettingsActionHandler} in addition to the handlers
     * configured by the base class.
     *
     * @param binding The instance multi-binding used to register action handlers.
     */
    protected override configureActionHandlers(binding: InstanceMultiBinding<ActionHandlerConstructor>): void {
        super.configureActionHandlers(binding);
        binding.add(RequestCreateEdgeSchemaActionHandler);
        binding.add(RevealSourceActionHandler);
        binding.add(UpdateEditorSettingsActionHandler);
    }

    /**
     * Configures additional bindings in the GLSP container.
     *
     * @param _context The binding context
     */
    protected configureAdditional(_context: BindingContext): void {}

    /**
     * Binds {@link ModelState} as the GLSP model state implementation.
     *
     * @returns The binding target for {@link ModelState}.
     */
    protected override bindModelState(): BindingTarget<ModelState> {
        return ModelState;
    }

    /**
     * Binds {@link GModelIndex} as the GLSP GModel index implementation.
     *
     * @returns The binding target for {@link GModelIndex}.
     */
    protected override bindGModelIndex(): BindingTarget<GModelIndex> {
        return GModelIndex;
    }

    /**
     * Binds {@link SourceModelStorage} as the GLSP source model storage implementation.
     *
     * @returns The binding target for {@link SourceModelStorage}.
     */
    protected override bindSourceModelStorage(): BindingTarget<SourceModelStorage> {
        return SourceModelStorage;
    }

    /**
     * Binds {@link ExtendedContextEditValidatorRegistry} as the context-edit validator registry.
     *
     * @returns The binding target for the {@link ContextEditValidatorRegistry}.
     */
    protected override bindContextEditValidatorRegistry(): BindingTarget<ContextEditValidatorRegistry> {
        return ExtendedContextEditValidatorRegistry;
    }

    /**
     * Binds {@link LangiumModelValidator} as the GLSP model validator implementation.
     *
     * @returns The binding target for the {@link ModelValidatorType}.
     */
    protected override bindModelValidator(): BindingTarget<ModelValidatorType> {
        return LangiumModelValidator;
    }

    /**
     * Binds {@link ModelSubmissionHandler} as the GLSP model submission handler.
     *
     * @returns The binding target for the {@link ModelSubmissionHandler}.
     */
    protected override bindModelSubmissionHandler(): BindingTarget<ModelSubmissionHandler> {
        return ModelSubmissionHandler;
    }

    /**
     * Registers the standard set of operation handlers: layout, reset-layout,
     * change-bounds, partial change-bounds, trigger-action, and
     * update-routing-information handlers.
     *
     * @param binding The instance multi-binding used to register operation handlers.
     */
    protected override configureOperationHandlers(binding: InstanceMultiBinding<OperationHandlerConstructor>): void {
        binding.add(CompoundOperationHandler);
        binding.add(UpdateClientOperationHandler);
        binding.add(ChangeBoundsOperationHandler);
        binding.add(PartialChangeBoundsOperationHandler);
        binding.add(TriggerActionOperationHandler);
        binding.add(UpdateRoutingInformationOperationHandler);
        binding.add(LayoutOperationHandler);
        binding.add(ResetLayoutOperationHandler);
        binding.add(BaseCutOperationHandler);
    }

    /**
     * Binds the model ID provider for generating unique IDs in the diagram.
     *
     * @returns The binding target for the ModelIdProvider
     */
    protected abstract bindModelIdProvider(): BindingTarget<ModelIdProvider>;

    /**
     * Binds the metadata manager for handling graph metadata.
     *
     * @returns The binding target for the MetadataManager
     */
    protected abstract bindMetadataManager(): BindingTarget<MetadataManager>;

    /**
     * Binds the layout engine for performing diagram layouting.
     *
     * @returns The binding target for the BaseLayoutEngine
     */
    protected abstract bindCustomLayoutEngine(): BindingTarget<BaseLayoutEngine>;

    /**
     * Binds the backend create-edge schema resolver used to supply edge type
     * options to the client.
     *
     * @returns The binding target for the {@link CreateEdgeSchemaResolver}.
     */
    protected abstract bindCreateEdgeSchemaResolver(): BindingTarget<CreateEdgeSchemaResolver>;

    /**
     * Binds the provider that supplies the set of names already present in the
     * target document. Override in language-specific diagram modules to include
     * additional names (e.g. from imported files).
     *
     * @returns The binding target for {@link ExistingNamesProvider}.
     */
    protected bindExistingNamesProvider(): BindingTarget<IExistingNamesProvider> {
        return DefaultExistingNamesProvider;
    }
}
