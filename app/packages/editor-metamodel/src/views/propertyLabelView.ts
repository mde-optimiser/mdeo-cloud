import type { GLabel } from "@mdeo/editor-shared";
import { GLabelView, sharedImport } from "@mdeo/editor-shared";
import { AddPropertyOperation } from "@mdeo/protocol-metamodel";
import type { Operation } from "@eclipse-glsp/protocol";

const { injectable } = sharedImport("inversify");

/**
 * Label view for metamodel property labels.
 * Handles commit for newly created property labels by dispatching {@link AddPropertyOperation}.
 */
@injectable()
export class GPropertyLabelView extends GLabelView {
    /**
     * Creates the operation to dispatch when a new property label is committed.
     *
     * @param model The label model element
     * @param editText The committed edit text
     * @returns The operation to dispatch
     */
    protected override createNewLabelOperation(model: Readonly<GLabel>, editText: string): Operation {
        if (model.newLabelOperationKind !== AddPropertyOperation.NEW_LABEL_KIND) {
            return super.createNewLabelOperation(model, editText);
        }

        return AddPropertyOperation.create({ classId: model.parentElementId!, propertyName: editText });
    }
}
