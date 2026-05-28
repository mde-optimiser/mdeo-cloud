import type { GLabel } from "@mdeo/editor-shared";
import { GLabelView, sharedImport } from "@mdeo/editor-shared";
import { GClassNode } from "../model/classNode.js";

const { injectable } = sharedImport("inversify");
const { findParentByFeature } = sharedImport("@eclipse-glsp/sprotty");

/**
 * View for rendering Class name labels in metamodel diagrams.
 * Extends HtmlLabelView to provide bold and center-aligned styling.
 */
@injectable()
export class GClassLabelView extends GLabelView {
    protected override getClasses(model: Readonly<GLabel>): Record<string, boolean> {
        const cls = findParentByFeature(model, (element) => element instanceof GClassNode);
        return {
            ...super.getClasses(model),
            "font-bold": true,
            italic: cls?.isAbstract ?? false,
            "text-center": true
        };
    }
}
