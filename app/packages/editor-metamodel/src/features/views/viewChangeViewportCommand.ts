import type {
    Args,
    SetModelAction as SetModelActionType,
    UpdateModelAction,
    GModelElementSchema
} from "@eclipse-glsp/protocol";
import type { CommandExecutionContext, CommandReturn, IActionDispatcher } from "@eclipse-glsp/sprotty";
import { sharedImport, LayoutAction, createFitToScreenAction } from "@mdeo/editor-shared";
import { MetamodelElementType, readMetamodelViewInfo } from "@mdeo/protocol-metamodel";

const { injectable, inject } = sharedImport("inversify");
const { Command, TYPES } = sharedImport("@eclipse-glsp/sprotty");

/**
 * Remembers which view the editor showed last, to tell a switch to another view from an
 * ordinary model update.
 */
@injectable()
export class MetamodelViewTracker {
    private initialized = false;
    private lastView: string | undefined;

    /**
     * Whether the view just shown is being laid out, and is to be fitted to the screen once
     * the laid-out model arrives.
     */
    fitAfterLayout = false;

    /**
     * Records the view a new model shows.
     *
     * @param view The view, or `undefined` for the whole metamodel
     * @returns `true` when the view differs from the one shown before; `false` for the first
     *          model, which the viewport initialisation takes care of
     */
    observe(view: string | undefined): boolean {
        const changed = this.initialized && view !== this.lastView;
        if (changed) {
            this.fitAfterLayout = false;
        }
        this.initialized = true;
        this.lastView = view;
        return changed;
    }
}

/**
 * Command run for every new model that adjusts the viewport when the editor switched to
 * another view.
 *
 * A view that has never been laid out — all its classes and enums still sit at the origin —
 * is laid out automatically, just as a diagram opened for the first time, and fitted to the
 * screen once the layout arrives; any other view is fitted to the screen right away, since its
 * layout lies wherever it was left.
 */
@injectable()
export class MetamodelViewChangeCommand extends Command {
    @inject(TYPES.IActionDispatcher)
    protected readonly actionDispatcher!: IActionDispatcher;

    @inject(MetamodelViewTracker)
    protected readonly tracker!: MetamodelViewTracker;

    constructor(@inject(TYPES.Action) protected readonly action: SetModelActionType | UpdateModelAction) {
        super();
    }

    execute(context: CommandExecutionContext): CommandReturn {
        const newRoot = this.action.newRoot;
        if (newRoot == undefined) {
            return context.root;
        }
        const { activeView } = readMetamodelViewInfo((newRoot as { args?: Args }).args);
        const changed = this.tracker.observe(activeView);
        const nodes = (newRoot.children ?? []).filter(
            (child) => child.type === MetamodelElementType.NODE_CLASS || child.type === MetamodelElementType.NODE_ENUM
        );
        const unplaced = nodes.every((node) => isAtOrigin(node));

        if (!changed) {
            // The model laid out after switching views: show where the layout put it.
            if (this.tracker.fitAfterLayout && nodes.length > 0 && !unplaced) {
                this.tracker.fitAfterLayout = false;
                this.fitToScreen(nodes);
            }
            return context.root;
        }

        if (nodes.length === 0) {
            return context.root;
        }
        if (unplaced) {
            this.tracker.fitAfterLayout = true;
            this.actionDispatcher.dispatch(LayoutAction.create());
        } else {
            this.fitToScreen(nodes);
        }
        return context.root;
    }

    /**
     * Fits the viewport to the given nodes.
     *
     * @param nodes The class and enum nodes of the new model
     */
    protected fitToScreen(nodes: GModelElementSchema[]): void {
        this.actionDispatcher.dispatch(
            createFitToScreenAction(
                true,
                nodes.map((node) => node.id)
            )
        );
    }

    undo(context: CommandExecutionContext): CommandReturn {
        return context.root;
    }

    redo(context: CommandExecutionContext): CommandReturn {
        return context.root;
    }
}

/**
 * Checks whether a node of a model sent by the server has no position yet.
 *
 * @param node The node schema
 * @returns `true` when the node lies at the origin
 */
function isAtOrigin(node: GModelElementSchema): boolean {
    const position = (node as { meta?: { position?: { x: number; y: number } } }).meta?.position;
    return position == undefined || (position.x === 0 && position.y === 0);
}
