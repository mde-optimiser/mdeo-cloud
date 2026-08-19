import { GLabelView, sharedImport } from "@mdeo/editor-shared";
import type { GLabel } from "@mdeo/editor-shared";
import { AddWhereClauseOperation } from "@mdeo/protocol-model-transformation";
import type { Operation } from "@eclipse-glsp/protocol";

const { injectable } = sharedImport("inversify");

/**
 * View for rendering where clause labels in model transformation diagrams.
 * Handles commit for newly created where clause labels by dispatching
 * {@link AddWhereClauseOperation}.
 */
@injectable()
export class GWhereClauseLabelView extends GLabelView {
    /**
     * Creates the operation to dispatch when a new where clause label is committed.
     *
     * The full edited text (e.g. {@code where a.b == c.d}) is forwarded verbatim so
     * the server can insert it at the correct source location. A clause that belongs to an
     * application condition block sits on the match node like any other, so the block it was
     * created for is read from the label's operation arguments.
     *
     * @param model The label model element
     * @param editText The committed edit text
     * @returns The operation to dispatch
     */
    protected override createNewLabelOperation(model: Readonly<GLabel>, editText: string): Operation {
        if (model.newLabelOperationKind !== AddWhereClauseOperation.NEW_LABEL_KIND) {
            return super.createNewLabelOperation(model, editText);
        }

        const conditionIndex = model.newLabelOperationArgs?.conditionIndex;

        return AddWhereClauseOperation.create({
            matchNodeId: model.parentElementId!,
            labelText: editText,
            conditionIndex: typeof conditionIndex === "number" ? conditionIndex : undefined
        });
    }
}
