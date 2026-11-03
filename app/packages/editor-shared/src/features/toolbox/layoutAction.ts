import type { Action } from "@eclipse-glsp/protocol";
import { sharedImport } from "../../sharedImport.js";
import type { ActionDispatcher, CommandExecutionContext, CommandReturn } from "@eclipse-glsp/sprotty";
import type { LayoutAlgorithmKind, LayoutOperation } from "@mdeo/protocol-common";
import type { EditorContextService } from "@eclipse-glsp/client";
import { readDefaultLayoutAlgorithm } from "./layoutAlgorithmPreference.js";

const { injectable, inject } = sharedImport("inversify");
const { Command, TYPES, isBoundsAware } = sharedImport("@eclipse-glsp/sprotty");
const { EditorContextService: EditorContextServiceKey } = sharedImport("@eclipse-glsp/client");

/**
 * Action to trigger layouting of the diagram
 */
export interface LayoutAction extends Action {
    kind: typeof LayoutAction.KIND;
    /**
     * The algorithm to use; the default algorithm of the diagram type (see the layout panel) if undefined
     */
    algorithm?: LayoutAlgorithmKind;
}

export namespace LayoutAction {
    export const KIND = "layout-action";

    /**
     * Creates a new layout action
     *
     * @param algorithm the algorithm to use, the default algorithm of the diagram type if undefined
     * @return the created layout action
     */
    export function create(algorithm?: LayoutAlgorithmKind): LayoutAction {
        return {
            kind: KIND,
            algorithm
        };
    }
}

/**
 * Command to perform layouting of the diagram
 */
@injectable()
export class LayoutCommand extends Command {
    static readonly KIND = LayoutAction.KIND;

    /**
     * The action dispatcher, used to dispatch actions
     */
    @inject(TYPES.IActionDispatcher) protected readonly actionDispatcher!: ActionDispatcher;

    /**
     * The editor context, provides the diagram type
     */
    @inject(EditorContextServiceKey) protected readonly editorContext!: EditorContextService;

    constructor(@inject(TYPES.Action) protected readonly action: LayoutAction) {
        super();
    }

    override execute(context: CommandExecutionContext): CommandReturn {
        const bounds = Object.fromEntries(
            [...context.root.index.all()]
                .filter(isBoundsAware)
                .map((element) => [element.id, { width: element.bounds.width, height: element.bounds.height }])
        );

        const operation: LayoutOperation = {
            kind: "layout",
            isOperation: true,
            bounds,
            algorithm: this.action.algorithm ?? readDefaultLayoutAlgorithm(this.editorContext.diagramType)
        };
        this.actionDispatcher.dispatch(operation);
        return context.root;
    }

    override undo(): CommandReturn {
        throw new Error("Method not implemented.");
    }

    override redo(): CommandReturn {
        throw new Error("Method not implemented.");
    }
}
