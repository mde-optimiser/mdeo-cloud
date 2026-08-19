import type { VNode } from "snabbdom";
import { sharedImport, Toolbox, ToolType } from "@mdeo/editor-shared";
import { generateEdgeTypeExtensionView } from "./edgeTypeExtensionView.js";
import { EdgeCreationType } from "@mdeo/protocol-metamodel";

const { injectable } = sharedImport("inversify");

/**
 * Extended toolbox for the metamodel editor.
 * Adds edge type selection to control which kind of association or inheritance
 * edge is created when using the connection tool.
 */
@injectable()
export class MetamodelToolbox extends Toolbox {
    public selectedEdgeType: EdgeCreationType = EdgeCreationType.UNIDIRECTIONAL;

    /**
     * Updates the selected edge creation type and re-renders the toolbox.
     *
     * @param edgeType The edge creation type to select
     */
    selectEdgeType(edgeType: EdgeCreationType): void {
        this.selectedEdgeType = edgeType;
        if (this.toolType !== ToolType.CREATE_EDGE) {
            this.updateTool(ToolType.CREATE_EDGE);
        } else {
            this.update();
        }
    }

    override generateDetailsExtension(): VNode[] {
        return [generateEdgeTypeExtensionView(this)];
    }
}
