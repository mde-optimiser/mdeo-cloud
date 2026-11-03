import type { Action, GModelElement, KeyListener as KeyListenerType } from "@eclipse-glsp/sprotty";
import type { Args } from "@eclipse-glsp/protocol";
import { sharedImport } from "@mdeo/editor-shared";
import { ConfirmViewDeletionAction, MetamodelElementType, readMetamodelViewInfo } from "@mdeo/protocol-metamodel";

const { injectable } = sharedImport("inversify");
const { KeyListener, matchesKeystroke, isDeletable, isSelectable } = sharedImport("@eclipse-glsp/sprotty");
const { DelKeyDeleteTool, DeleteElementOperation } = sharedImport("@eclipse-glsp/client");

/**
 * Delete-key listener of the metamodel editor.
 *
 * Outside a view it deletes the selection, as the default listener does. Inside a view,
 * deleting a class or enum could mean two things — hiding it in this view or removing it from
 * the metamodel — so the listener asks first (see {@link ConfirmViewDeletionAction}). A
 * selection of associations and inheritance relations only is deleted right away: a view shows
 * them because it shows both their ends, so they cannot be removed from it on their own.
 */
export class MetamodelDeleteKeyListener extends (KeyListener as new () => KeyListenerType) {
    override keyDown(element: GModelElement, event: KeyboardEvent): Action[] {
        if (!matchesKeystroke(event, "Delete")) {
            return [];
        }
        const selected = [...element.root.index.all()]
            .filter((e) => isDeletable(e) && isSelectable(e) && e.selected)
            .filter((e) => e.id !== e.root.id);
        if (selected.length === 0) {
            return [];
        }

        const { activeView } = readMetamodelViewInfo((element.root as unknown as { args?: Args }).args);
        const members = selected.filter(
            (e) => e.type === MetamodelElementType.NODE_CLASS || e.type === MetamodelElementType.NODE_ENUM
        );
        if (activeView == undefined || members.length === 0) {
            return [DeleteElementOperation.create(selected.map((e) => e.id))];
        }

        // Imported elements live in another file; a view can drop them, but they cannot be
        // deleted from here.
        const deletable = selected.filter((e) => !(e.cssClasses ?? []).includes("imported"));
        return [
            ConfirmViewDeletionAction.create(
                deletable.map((e) => e.id),
                members.map((e) => e.id),
                activeView
            )
        ];
    }
}

/**
 * Delete-key tool of the metamodel editor, using {@link MetamodelDeleteKeyListener}.
 */
@injectable()
export class MetamodelDelKeyDeleteTool extends DelKeyDeleteTool {
    protected override deleteKeyListener = new MetamodelDeleteKeyListener() as unknown as InstanceType<
        typeof DelKeyDeleteTool
    >["deleteKeyListener"];
}
