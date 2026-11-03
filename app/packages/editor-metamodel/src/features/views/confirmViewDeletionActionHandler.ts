import type { Action, IActionHandler, GModelElement } from "@eclipse-glsp/sprotty";
import { sharedImport, ChoiceDialog, type ChoiceDialogChoice } from "@mdeo/editor-shared";
import { ConfirmViewDeletionAction, RemoveFromMetamodelViewOperation } from "@mdeo/protocol-metamodel";

const { injectable, inject } = sharedImport("inversify");
const { DeleteElementOperation } = sharedImport("@eclipse-glsp/protocol");
const { EditorContextService: EditorContextServiceKey } = sharedImport("@eclipse-glsp/client");

/**
 * Answers a {@link ConfirmViewDeletionAction} with a dialog asking whether the classes and enums
 * are removed from the view only or deleted from the metamodel.
 */
@injectable()
export class ConfirmViewDeletionActionHandler implements IActionHandler {
    @inject(ChoiceDialog)
    protected readonly choiceDialog!: ChoiceDialog;

    @inject(EditorContextServiceKey)
    protected readonly editorContext!: InstanceType<typeof EditorContextServiceKey>;

    handle(action: Action): void {
        if (!ConfirmViewDeletionAction.is(action)) {
            return;
        }

        const choices: ChoiceDialogChoice[] = [
            {
                label: "Remove from View",
                variant: "primary",
                actions: [RemoveFromMetamodelViewOperation.create(action.viewMemberIds, action.view)]
            }
        ];
        if (action.elementIds.length > 0) {
            choices.push({
                label: "Delete Completely",
                variant: "destructive",
                actions: [DeleteElementOperation.create(action.elementIds)]
            });
        }

        const single = action.viewMemberIds.length === 1;
        const name = single ? this.elementName(action.viewMemberIds[0]) : undefined;
        const subject = single
            ? name != undefined
                ? `"${name}"`
                : "this element"
            : `${action.viewMemberIds.length} elements`;
        const pronoun = single ? "it" : "them";
        const others = action.elementIds.some((id) => !action.viewMemberIds.includes(id))
            ? " Other selected elements are only affected by deleting completely."
            : "";

        this.choiceDialog.show({
            title: `Delete ${subject}?`,
            message:
                `Remove ${pronoun} from the view "${action.view}" only, or delete ${pronoun} from the metamodel ` +
                `and every view?${others}`,
            choices
        });
    }

    /**
     * Looks up the name shown on a class or enum node.
     *
     * @param id The id of the node
     * @returns The name, or `undefined` when the node is not found
     */
    protected elementName(id: string): string | undefined {
        const label = this.editorContext.modelRoot.index.getById(`${id}__name`) as
            (GModelElement & { text?: string }) | undefined;
        return label?.text;
    }
}
