import { GLabelView, sharedImport } from "@mdeo/editor-shared";
import type { GLabel } from "@mdeo/editor-shared";
import { AddVariableOperation } from "@mdeo/protocol-model-transformation";
import type { Operation } from "@eclipse-glsp/protocol";

const { injectable } = sharedImport("inversify");

/**
 * View for rendering variable labels in model transformation diagrams.
 * Handles commit for newly created variable labels by dispatching
 * {@link AddVariableOperation}.
 */
@injectable()
export class GVariableLabelView extends GLabelView {
    /**
     * Creates the operation to dispatch when a new variable label is committed.
     *
     * The full edited text (e.g. {@code var name[: type] = expression}) is forwarded
     * verbatim so the server can insert it at the correct source location.
     *
     * @param model The label model element
     * @param editText The committed edit text
     * @returns The operation to dispatch
     */
    protected override createNewLabelOperation(model: Readonly<GLabel>, editText: string): Operation {
        if (model.newLabelOperationKind !== AddVariableOperation.NEW_LABEL_KIND) {
            return super.createNewLabelOperation(model, editText);
        }
        return AddVariableOperation.create({
            matchNodeId: model.parentElementId!,
            labelText: editText
        });
    }
}
