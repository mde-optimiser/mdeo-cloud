import {
    sharedImport,
    Toolbox,
    type CreateEdgeContextProvider as ICreateEdgeContextProvider
} from "@mdeo/editor-shared";
import type { GNode } from "@mdeo/editor-shared";
import type { MetamodelToolbox } from "./metamodelToolbox.js";
import type { MetamodelEdgeCreationContext } from "@mdeo/protocol-metamodel";

const { injectable, inject } = sharedImport("inversify");

/**
 * Provides the selected edge type from the metamodel toolbox as context for
 * create-edge schema resolution.
 * The context is forwarded to the server so it can determine the association operator.
 */
@injectable()
export class MetamodelCreateEdgeContextProvider implements ICreateEdgeContextProvider {
    @inject(Toolbox)
    protected readonly toolbox!: MetamodelToolbox;

    getInitialContext(_source: GNode): MetamodelEdgeCreationContext {
        return { edgeType: this.toolbox.selectedEdgeType };
    }

    getTargetContext(_source: GNode, _target: GNode): MetamodelEdgeCreationContext {
        return { edgeType: this.toolbox.selectedEdgeType };
    }
}
