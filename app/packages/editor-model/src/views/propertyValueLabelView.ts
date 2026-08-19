import type { GLabel } from "@mdeo/editor-shared";
import { GLabelView, sharedImport } from "@mdeo/editor-shared";
import { AddPropertyValueOperation } from "@mdeo/protocol-model";
import type { Operation } from "@eclipse-glsp/protocol";

const { injectable } = sharedImport("inversify");

/**
 * Label view for model property value labels.
 * Handles commit for newly created property value labels by dispatching
 * {@link AddPropertyValueOperation}.
 *
 * A pre-selected property is prefilled into the label text and recorded in the label ID; the
 * operation kind names nothing but the operation.
 */
@injectable()
export class GPropertyValueLabelView extends GLabelView {
    /**
     * Creates the operation to dispatch when a new property value label is committed.
     *
     * The full edited text (e.g. {@code propName = value}) is forwarded verbatim so
     * that the server can insert it at the correct source location.
     *
     * @param model The label model element
     * @param editText The committed edit text
     * @returns The operation to dispatch
     */
    protected override createNewLabelOperation(model: Readonly<GLabel>, editText: string): Operation {
        if (model.newLabelOperationKind !== AddPropertyValueOperation.NEW_LABEL_KIND) {
            return super.createNewLabelOperation(model, editText);
        }

        return AddPropertyValueOperation.create({
            objectId: model.parentElementId!,
            labelText: editText
        });
    }
}
