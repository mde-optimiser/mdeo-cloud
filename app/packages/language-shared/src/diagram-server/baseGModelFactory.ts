import type { GModelFactory, GModelRoot } from "@eclipse-glsp/server";
import { sharedImport } from "../sharedImport.js";
import type { AstNode } from "langium";
import { type ModelIdRegistry, DefaultModelIdRegistry } from "./modelIdRegistry.js";
import { ModelIdProvider } from "./modelIdProvider.js";
import type { ModelState } from "./modelState.js";
import type { GModelIndex } from "./modelIndex.js";
import type { AstReflection } from "@mdeo/language-common";

const { injectable, inject } = sharedImport("inversify");
const { ModelState: ModelStateKey, GModelIndex: GModelIndexKey } = sharedImport("@eclipse-glsp/server");

/**
 * Base class for graph model factories that create GLSP models from source AST models.
 * Handles id generation and model index updates
 */
@injectable()
export abstract class BaseGModelFactory<T extends AstNode> implements GModelFactory {
    /**
     * Injected model state
     */
    @inject(ModelStateKey)
    protected modelState!: ModelState<T>;

    /**
     * Injected model ID provider for generating unique IDs
     */
    @inject(ModelIdProvider)
    protected modelIdProvider!: ModelIdProvider;

    /**
     * Injected model index
     */
    @inject(GModelIndexKey)
    protected modelIndex!: GModelIndex;

    /**
     * AST reflection utilities
     */
    protected get reflection(): AstReflection {
        return this.modelState.languageServices.shared.AstReflection;
    }

    createModel(): void {
        throw new Error("Use createModelAsync instead.");
    }

    async createModelAsync(): Promise<void> {
        const sourceModel = this.modelState.sourceModel;
        if (sourceModel == undefined) {
            return;
        }
        const idRegistry = new DefaultModelIdRegistry(sourceModel, this.modelIdProvider);
        const gRoot = await this.createModelInternal(sourceModel, idRegistry);
        this.modelIndex.indexSourceModelRoot(sourceModel, idRegistry);
        this.modelState.updateRoot(gRoot);
    }

    /**
     * Creates the graph model from the current source model.
     *
     * @param sourceModel the source AST model
     * @param idRegistry the model ID registry
     * @returns the created graph model root
     */
    abstract createModelInternal(sourceModel: T, idRegistry: ModelIdRegistry): GModelRoot | Promise<GModelRoot>;
}
