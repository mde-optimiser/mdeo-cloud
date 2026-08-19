import type { Action, MaybePromise } from "@eclipse-glsp/server";
import { RevealSourceAction } from "@mdeo/protocol-common";
import { createRevealSourceProtocol } from "@mdeo/language-common";
import type { ModelState } from "../modelState.js";
import type { GModelIndex } from "../modelIndex.js";
import { sharedImport } from "../../sharedImport.js";

const { injectable, inject } = sharedImport("inversify");
const vscodeJsonrpc = sharedImport("vscode-jsonrpc");
const { AstUtils } = sharedImport("langium");
const { GModelIndex: GModelIndexKey, ModelState: ModelStateKey } = sharedImport("@eclipse-glsp/server");

/**
 * GLSP server-side action handler for {@link RevealSourceAction}.
 *
 * <p>When the graphical editor client dispatches a {@code revealSource} action (triggered
 * by alt+click or double-click on an issue marker), this handler:
 * <ol>
 *   <li>Resolves the GModel element with the given ID from the index.</li>
 *   <li>Looks up the corresponding Langium AST node.</li>
 *   <li>Reads the AST node's CST source range.</li>
 *   <li>Sends a {@code textDocument/revealSource} LSP notification to the workbench,
 *       which in turn reveals the range in the Monaco textual editor.</li>
 * </ol>
 *
 * <p>Registered in {@link BaseDiagramModule#configureActionHandlers}.
 */
@injectable()
export class RevealSourceActionHandler {
    /**
     * The GLSP action kinds handled by this handler.
     */
    readonly actionKinds = [RevealSourceAction.KIND];

    /**
     * The GLSP model state providing access to source model and LSP connection.
     */
    @inject(ModelStateKey)
    protected modelState!: ModelState;

    /**
     * The GModel index used to look up graphical elements and their corresponding AST nodes.
     */
    @inject(GModelIndexKey)
    protected index!: GModelIndex;

    /**
     * Handles the {@link RevealSourceAction} by locating the AST node and notifying
     * the workbench to reveal the corresponding source range.
     *
     * @param action The incoming reveal-source action.
     * @returns An empty action list (this handler produces no model changes).
     */
    execute(action: RevealSourceAction): MaybePromise<Action[]> {
        const connection = this.modelState.languageServices.shared.lsp.Connection;

        if (connection == undefined) {
            return [];
        }

        const gModelElement = this.index.find(action.elementId);
        if (gModelElement == undefined) {
            return [];
        }

        const astNode = this.index.getAstNode(gModelElement);
        if (astNode == undefined) {
            return [];
        }

        const cstNode = astNode.$cstNode;
        if (cstNode == undefined) {
            return [];
        }

        // Use the document the AST node actually lives in — it may be an imported file
        // that is different from the diagram's primary source document.
        const document = AstUtils.getDocument(astNode);
        const uri = document.uri.toString();

        const protocol = createRevealSourceProtocol(vscodeJsonrpc);
        connection.sendNotification(protocol.RevealSourceNotification, {
            uri,
            range: cstNode.range
        });

        return [];
    }
}
