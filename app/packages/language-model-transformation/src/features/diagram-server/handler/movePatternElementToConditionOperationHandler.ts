import { BaseOperationHandler, OperationHandlerCommand, sharedImport } from "@mdeo/language-shared";
import type { Command } from "@eclipse-glsp/server";
import {
    MovePatternElementToConditionOperation,
    MAIN_PATTERN_TARGET,
    NEW_FORBID_TARGET,
    NEW_REQUIRE_TARGET
} from "@mdeo/protocol-model-transformation";
import type { WorkspaceEdit } from "vscode-languageserver-types";
import { PatternApplicationCondition, type PatternType } from "../../../grammar/modelTransformationTypes.js";
import { patternConditions } from "../modelTransformationPatternUtils.js";
import {
    canEnterCondition,
    containerBraces,
    movableComponent,
    removedCstNodes,
    type ConditionKind,
    type PatternElementContainer
} from "../modelTransformationConditionMoves.js";

const { injectable } = sharedImport("inversify");

/**
 * Handler that moves a pattern element into another application condition block, into a new
 * block, or back into the enclosing match pattern.
 *
 * Because a block is a graph of its own, an element never travels alone: the whole connected
 * component it belongs to inside its current container is moved with it. Moving a node out of
 * a block while leaving its links behind would otherwise split one graph into two halves that
 * can no longer refer to each other.
 *
 * Where clauses move as well, and they move alone — a clause constrains a graph, it is not
 * part of it. A clause that reads a node of its current block cannot leave the block, since
 * that name means nothing anywhere else; the move is then not offered.
 *
 * A move never produces source that the validator would reject. Entering a block is
 * therefore refused for anything a block cannot hold — a modifier, a property assignment, an
 * element its grammar does not admit — and for a component whose names are still read by
 * what stays behind, since a block-local name means nothing outside its block. Leaving a
 * block does not leave an empty one behind either: a block goes with its last member.
 *
 * The destinations are offered in the "Change Modifier" menu of
 * {@link ChangePatternElementModifierOperationHandler}: which block an element belongs to is
 * what the `forbid` / `require` modifiers used to say, so the choice belongs next to the
 * remaining modifiers rather than in a menu of its own. Both sides read the same rules from
 * {@link modelTransformationConditionMoves}.
 */
@injectable()
export class MovePatternElementToConditionOperationHandler extends BaseOperationHandler {
    override readonly operationType = MovePatternElementToConditionOperation.KIND;

    /**
     * Creates the command that performs the move.
     *
     * The moved elements are cut from their current container and re-inserted verbatim into
     * the destination, so that formatting, comments and property constraints survive the move.
     *
     * @param operation The requested move
     * @returns A command applying the workspace edit, or `undefined` when the element cannot
     *          be resolved or the move would produce source the validator rejects
     */
    override async createCommand(operation: MovePatternElementToConditionOperation): Promise<Command | undefined> {
        const element = this.modelState.index.find(operation.elementId);
        if (element == undefined) {
            return undefined;
        }
        const astNode = this.index.getAstNode(element);
        if (astNode == undefined) {
            return undefined;
        }

        const movable = movableComponent(astNode, this.reflection);
        if (movable == undefined) {
            return undefined;
        }

        const target = this.resolveTarget(movable.pattern, movable.container, operation.target);
        if (target === undefined) {
            return undefined;
        }
        if (this.entersCondition(target) && !canEnterCondition(movable.elements, movable.container, this.reflection)) {
            return undefined;
        }

        const movedText = movable.elements
            .map((node) => node.$cstNode?.text)
            .filter((text): text is string => text != undefined && text.length > 0);
        if (movedText.length !== movable.elements.length) {
            return undefined;
        }

        const edits = removedCstNodes(movable.elements, movable.container, this.reflection).map((cstNode) =>
            this.deleteCstNode(cstNode)
        );

        const insertion =
            target.kind === "new"
                ? this.insertNewBlock(movable.pattern, target.conditionKind, movedText)
                : this.insertIntoExisting(target.container, movedText);
        if (insertion == undefined) {
            return undefined;
        }
        edits.push(insertion);

        return new OperationHandlerCommand(this.modelState, this.mergeWorkspaceEdits(edits), undefined);
    }

    /**
     * Checks whether a destination lies inside an application condition block.
     *
     * @param target The resolved destination
     * @returns `true` when the component would end up inside a block
     */
    private entersCondition(
        target: { kind: "existing"; container: PatternElementContainer } | { kind: "new"; conditionKind: ConditionKind }
    ): boolean {
        return target.kind === "new" || this.reflection.isInstance(target.container, PatternApplicationCondition);
    }

    /**
     * Resolves the requested destination.
     *
     * @param pattern The enclosing pattern
     * @param container The container the element currently lives in
     * @param target The requested destination
     * @returns The resolved destination, or `undefined` when it does not exist or equals the
     *          current container
     */
    private resolveTarget(
        pattern: PatternType,
        container: PatternElementContainer,
        target: string
    ):
        | { kind: "existing"; container: PatternElementContainer }
        | { kind: "new"; conditionKind: ConditionKind }
        | undefined {
        if (target === MAIN_PATTERN_TARGET) {
            return container === pattern ? undefined : { kind: "existing", container: pattern };
        }
        if (target === NEW_FORBID_TARGET) {
            return { kind: "new", conditionKind: "forbid" };
        }
        if (target === NEW_REQUIRE_TARGET) {
            return { kind: "new", conditionKind: "require" };
        }

        const index = Number.parseInt(target, 10);
        const conditions = patternConditions(pattern, this.reflection);
        if (Number.isNaN(index) || index < 0 || index >= conditions.length) {
            return undefined;
        }
        const condition = conditions[index];
        return condition === container ? undefined : { kind: "existing", container: condition };
    }

    /**
     * Builds the edit inserting the moved elements into an existing container.
     *
     * @param container The destination pattern or block
     * @param movedText The source text of the moved elements
     * @returns The workspace edit, or `undefined` when the container has no braces
     */
    private insertIntoExisting(container: PatternElementContainer, movedText: string[]): WorkspaceEdit | undefined {
        const braces = containerBraces(container);
        if (braces == undefined) {
            return undefined;
        }
        return this.insertIntoScope(braces.open, braces.close, true, movedText.join("\n"));
    }

    /**
     * Builds the edit creating a new condition block that holds the moved elements.
     *
     * @param pattern The pattern the block is appended to
     * @param conditionKind Whether a `forbid` or a `require` block is created
     * @param movedText The source text of the moved elements
     * @returns The workspace edit, or `undefined` when the pattern has no braces
     */
    private insertNewBlock(
        pattern: PatternType,
        conditionKind: ConditionKind,
        movedText: string[]
    ): WorkspaceEdit | undefined {
        const braces = containerBraces(pattern);
        if (braces == undefined) {
            return undefined;
        }

        const body = movedText.map((text) => `    ${text}`).join("\n");
        return this.insertIntoScope(braces.open, braces.close, true, `${conditionKind} {\n${body}\n}`);
    }
}
