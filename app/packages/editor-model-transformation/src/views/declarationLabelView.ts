import { GLabelView, sharedImport } from "@mdeo/editor-shared";
import type { GLabel } from "@mdeo/editor-shared";
import type { Operation } from "@eclipse-glsp/protocol";
import type { RenderingContext } from "@eclipse-glsp/sprotty";
import type { VNode, VNodeStyle } from "snabbdom";
import { AddHeaderDeclarationOperation } from "@mdeo/protocol-model-transformation";
import type { GDeclarationLabel } from "../model/declarationLabel.js";

const { injectable } = sharedImport("inversify");

/**
 * View for a declaration row of the start node or of an end node: the declaration in the font
 * of the source, with a highlight while selected. A new row (added from the toolbox) commits
 * through {@link AddHeaderDeclarationOperation}; its name is preselected for typing over it.
 */
@injectable()
export class GDeclarationLabelView extends GLabelView {
    override render(model: Readonly<GLabel>, context: RenderingContext): VNode | undefined {
        const vnode = super.render(model, context);
        if (vnode != undefined && !model.editMode) {
            // in the font of the source, like the text of a call node
            const style: VNodeStyle = { ...vnode.data?.style };
            style.fontFamily = "monospace";
            style.fontSize = "12px";
            style.borderRadius = "3px";
            vnode.data = { ...vnode.data, style };
        }
        return vnode;
    }

    protected override getClasses(model: Readonly<GLabel>): Record<string, boolean> {
        const selected = (model as GDeclarationLabel).selected === true;
        return {
            ...super.getClasses(model),
            "whitespace-pre": true,
            block: true,
            "px-1": true,
            "bg-accent": selected,
            "text-accent-foreground": selected
        };
    }

    protected override createNewLabelOperation(model: Readonly<GLabel>, editText: string): Operation {
        if (model.newLabelOperationKind !== AddHeaderDeclarationOperation.NEW_LABEL_KIND) {
            return super.createNewLabelOperation(model, editText);
        }
        return AddHeaderDeclarationOperation.create({
            nodeId: model.parentElementId!,
            labelText: editText
        });
    }

    /**
     * Focuses the edit control and selects the declaration's name (`input1` of
     * `in input1: int`), which is what a new row usually needs changed; without one, everything.
     *
     * @param element The edit control
     */
    protected override focusAndSelect(element: HTMLTextAreaElement): void {
        setTimeout(() => {
            element.focus();
            const name = /^(\s*(?:in|out)\s+)([^\s:]+)/.exec(element.value);
            if (name != undefined) {
                element.setSelectionRange(name[1].length, name[1].length + name[2].length);
            } else {
                element.select();
            }
        });
    }
}
