import type { GLabel } from "@mdeo/editor-shared";
import { GLabelView, sharedImport } from "@mdeo/editor-shared";
import { AddEnumEntryOperation } from "@mdeo/protocol-metamodel";
import type { Operation } from "@eclipse-glsp/protocol";

const { injectable } = sharedImport("inversify");

/**
 * Label view for enum entry labels.
 * Handles commit for newly created enum-entry labels by dispatching {@link AddEnumEntryOperation}.
 */
@injectable()
export class GEnumEntryLabelView extends GLabelView {
    /**
     * Creates the operation to dispatch when a new enum entry label is committed.
     *
     * @param model The label model element
     * @param editText The committed edit text
     * @returns The operation to dispatch
     */
    protected override createNewLabelOperation(model: Readonly<GLabel>, editText: string): Operation {
        if (model.newLabelOperationKind !== AddEnumEntryOperation.NEW_LABEL_KIND) {
            return super.createNewLabelOperation(model, editText);
        }

        return AddEnumEntryOperation.create({ enumId: model.parentElementId!, entryName: editText });
    }
}
