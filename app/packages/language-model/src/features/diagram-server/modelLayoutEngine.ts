import type { GModelRoot } from "@eclipse-glsp/server";
import type { LayoutOperation } from "@mdeo/protocol-common";
import { BaseLayoutEngine, GEdge, GNode, sharedImport } from "@mdeo/language-shared";
import type { EdgeLabelSpec, LayoutInput } from "@mdeo/language-shared";
import { GLinkEndNode } from "./model/linkEndNode.js";

const { injectable } = sharedImport("inversify");

/**
 * Layout engine for model diagrams (also used by the generated, read-only model view).
 */
@injectable()
export class ModelLayoutEngine extends BaseLayoutEngine {
    /**
     * Builds the layout input: objects become nodes, links edges with their end labels.
     *
     * @param model The GModelRoot to transform
     * @param operation The layout operation containing bounds information
     * @returns The layout input
     */
    protected override buildInput(model: GModelRoot, operation: LayoutOperation): LayoutInput {
        const bounds = operation.bounds;
        const input: LayoutInput = { nodes: [], edges: [], direction: "down" };
        for (const child of model.children) {
            if (child instanceof GNode) {
                input.nodes.push({ id: child.id, ...this.sizeOf(child.id, bounds) });
            } else if (child instanceof GEdge) {
                const labels: EdgeLabelSpec[] = child.children
                    .filter((label): label is GLinkEndNode => label instanceof GLinkEndNode)
                    .map((label) => this.labelSpec(label, label.end, "left", bounds));
                input.edges.push({ id: child.id, sourceId: child.sourceId, targetId: child.targetId, labels });
            }
        }
        return input;
    }
}
