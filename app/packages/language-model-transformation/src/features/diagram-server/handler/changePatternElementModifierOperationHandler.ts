import { BaseOperationHandler, OperationHandlerCommand, sharedImport } from "@mdeo/language-shared";
import type { Command, GModelElement } from "@eclipse-glsp/server";
import {
    ChangePatternElementModifierOperation,
    MovePatternElementToConditionOperation,
    ModelTransformationElementType,
    PatternModifierKind,
    MAIN_PATTERN_TARGET,
    NEW_FORBID_TARGET,
    NEW_REQUIRE_TARGET
} from "@mdeo/protocol-model-transformation";
import type { ContextActionRequestContext, ContextItemProvider } from "@mdeo/language-shared";
import type { ContextItem } from "@mdeo/protocol-common";
import {
    PatternObjectInstance,
    PatternApplicationCondition,
    PatternLink,
    type PatternObjectInstanceType,
    type PatternLinkType
} from "../../../grammar/modelTransformationTypes.js";
import {
    conditionMoveOptions,
    containerBraces,
    movableComponent,
    removedCstNodes,
    type ConditionKind,
    type ConditionKindOptions,
    type ConditionMoveOptions
} from "../modelTransformationConditionMoves.js";
import type { AstNode } from "langium";
import type { WorkspaceEdit } from "vscode-languageserver-types";

const { injectable } = sharedImport("inversify");
const { GrammarUtils } = sharedImport("langium");
const { TextEdit } = sharedImport("vscode-languageserver-types");

/**
 * Handler for changing the create/delete modifier on pattern nodes and links, and owner of
 * the "Change Modifier" menu.
 *
 * Negative and positive application conditions are no longer modifiers: they are blocks, and
 * an element joins one by being moved into it — see
 * {@link MovePatternElementToConditionOperationHandler}. That is a change of representation
 * rather than a change of meaning, so the menu still reads as it always did: `forbid` and
 * `require` sit next to `create` and `delete`, and only what a block needs and a modifier
 * does not — which of the pattern's blocks the element joins — is asked below them.
 *
 * An element declared inside a block carries the kind of that block rather than a modifier of
 * its own, so the menu answers for it by moving it: "None" returns it to the match pattern,
 * and `create` or `delete` — which a block may not hold — take it there and give it the
 * modifier in one edit.
 */
@injectable()
export class ChangePatternElementModifierOperationHandler extends BaseOperationHandler implements ContextItemProvider {
    override readonly operationType = ChangePatternElementModifierOperation.KIND;

    /**
     * Creates a command to apply a modifier change on the target pattern element.
     * Supports both inserting a new modifier (when none exists) and replacing or removing an existing one.
     * When the target element is a `PatternObjectInstance`, all adjacent `PatternLink` instances —
     * whether they have an existing modifier or none — are updated to the same modifier value so
     * that the pattern remains consistent.
     *
     * @param operation The change-modifier operation containing the element ID and the new modifier
     * @returns A command applying the workspace edit, or `undefined` when the element or AST node
     *          cannot be resolved
     */
    override async createCommand(operation: ChangePatternElementModifierOperation): Promise<Command | undefined> {
        const element = this.modelState.index.find(operation.elementId);
        if (element == undefined) {
            return undefined;
        }
        const node = this.index.getAstNode(element);
        if (node == undefined) {
            return undefined;
        }

        let edit: WorkspaceEdit | undefined;
        if (this.isInsideApplicationCondition(node)) {
            // Leaving a block without taking a modifier along is a plain move, and is
            // requested as one.
            edit =
                operation.modifier === PatternModifierKind.NONE
                    ? undefined
                    : this.createLeaveConditionEdit(node, operation.modifier);
        } else if (this.reflection.isInstance(node, PatternObjectInstance)) {
            edit = await this.createInstanceModifierEdit(node as PatternObjectInstanceType, operation.modifier);
        } else if (this.reflection.isInstance(node, PatternLink)) {
            edit = await this.createLinkModifierEdit(node as PatternLinkType, operation.modifier);
        }

        if (edit == undefined) {
            return undefined;
        }
        return new OperationHandlerCommand(this.modelState, edit, undefined);
    }

    /**
     * Returns the "Change Modifier" menu for a pattern instance or link.
     *
     * The menu holds what the element may become: `None`, `Create` and `Delete` when it can
     * carry a modifier, and the `Forbid` / `Require` blocks of its pattern when it may live
     * in one. A block entry lists the pattern's blocks of that kind and a "New Block", and
     * collapses into a single action when creating one is the only choice left, so a pattern
     * without blocks offers exactly the two entries it always did.
     *
     * @param element The selected element
     * @param _context - Additional request context
     * @returns The menu, or an empty array when the element can neither be changed nor moved
     */
    getContextItems(element: GModelElement, _context: ContextActionRequestContext): ContextItem[] {
        if (
            element.type !== ModelTransformationElementType.NODE_PATTERN_INSTANCE &&
            element.type !== ModelTransformationElementType.EDGE_PATTERN_LINK &&
            element.type !== ModelTransformationElementType.LABEL_WHERE_CLAUSE
        ) {
            return [];
        }
        const astNode = this.index.getAstNode(element);
        if (astNode == undefined) {
            return [];
        }

        const moves = conditionMoveOptions(astNode, this.reflection);
        // A block member takes its modifier by leaving the block, so it needs a way out.
        const modifiable =
            this.canCarryModifier(element, astNode) &&
            (!this.isInsideApplicationCondition(astNode) || moves?.inBlock === true);

        const children: ContextItem[] = [];
        const noneItem = this.buildNoneItem(element, modifiable, moves);
        if (noneItem != undefined) {
            children.push(noneItem);
        }
        if (modifiable) {
            children.push(
                this.buildModifierItem(element, "Create", PatternModifierKind.CREATE, "square-plus"),
                this.buildModifierItem(element, "Delete", PatternModifierKind.DELETE, "square-x")
            );
        }
        if (moves != undefined) {
            children.push(
                ...[
                    this.buildConditionItem(element, "forbid", moves.forbid),
                    this.buildConditionItem(element, "require", moves.require)
                ].filter((item): item is ContextItem => item != undefined)
            );
        }

        if (children.length === 0) {
            return [];
        }
        return [
            {
                id: `change-modifier-${element.id}`,
                label: "Change Modifier",
                icon: "square-dot",
                sortString: "c",
                children
            }
        ];
    }

    /**
     * Checks whether the element is one that can carry a modifier of its own.
     *
     * References, implicit references, and delete-references are excluded because their
     * modifier is not locally editable, and where clauses have none to begin with.
     *
     * For pattern links the modifier is only editable when both connected instances carry no
     * modifier, since a link that already shares a modifier with an instance should not be
     * changed in isolation.
     *
     * @param element The selected element
     * @param astNode The AST node behind it
     * @returns `true` when `Create` and `Delete` apply to the element
     */
    private canCarryModifier(element: GModelElement, astNode: AstNode): boolean {
        if (element.type === ModelTransformationElementType.NODE_PATTERN_INSTANCE) {
            return this.reflection.isInstance(astNode, PatternObjectInstance);
        }
        if (element.type !== ModelTransformationElementType.EDGE_PATTERN_LINK) {
            return false;
        }
        if (!this.reflection.isInstance(astNode, PatternLink)) {
            return false;
        }
        const link = astNode as PatternLinkType;
        return link.source?.object?.ref?.modifier == undefined && link.target?.object?.ref?.modifier == undefined;
    }

    /**
     * Builds the `None` entry: the element carries no modifier and belongs to no block.
     *
     * For a block member that is a move back into the match pattern; for anything else it
     * clears the modifier. An element that is neither editable nor movable out of its block
     * has nothing to return to, and gets no entry.
     *
     * @param element The selected element
     * @param modifiable Whether it can carry a modifier
     * @param moves The destinations it may move to, if any
     * @returns The entry, or `undefined` when it does not apply
     */
    private buildNoneItem(
        element: GModelElement,
        modifiable: boolean,
        moves: ConditionMoveOptions | undefined
    ): ContextItem | undefined {
        const id = `change-modifier-${element.id}-none`;
        if (moves?.inBlock === true) {
            return {
                id,
                label: "None",
                icon: "square",
                action: MovePatternElementToConditionOperation.create({
                    elementId: element.id,
                    target: MAIN_PATTERN_TARGET
                })
            };
        }
        if (!modifiable) {
            return undefined;
        }
        return {
            id,
            label: "None",
            icon: "square",
            action: ChangePatternElementModifierOperation.create({
                elementId: element.id,
                modifier: PatternModifierKind.NONE
            })
        };
    }

    /**
     * Builds an entry that assigns a modifier to the element.
     *
     * @param element The selected element
     * @param label The label of the entry
     * @param modifier The modifier it assigns
     * @param icon The icon of the entry
     * @returns The entry
     */
    private buildModifierItem(
        element: GModelElement,
        label: string,
        modifier: PatternModifierKind,
        icon: string
    ): ContextItem {
        return {
            id: `change-modifier-${element.id}-${modifier}`,
            label,
            icon,
            action: ChangePatternElementModifierOperation.create({ elementId: element.id, modifier })
        };
    }

    /**
     * Builds the `Forbid` or `Require` entry: the element joins a block of that kind.
     *
     * The blocks of the pattern are listed under the entry, since which one the element joins
     * is a question the two modifiers never had to ask — and one block still has to be named
     * to be told from the others, so a single block is listed as well. Only when the pattern
     * has no block of that kind does the entry become the action creating one, which is what
     * the modifier always did. An entry with nothing to offer disappears: an element already
     * alone in a `forbid` block has nothing left to forbid it with.
     *
     * @param element The selected element
     * @param kind The kind of block the entry stands for
     * @param options What the element may do with the blocks of that kind
     * @returns The entry, or `undefined` when the kind offers the element nothing
     */
    private buildConditionItem(
        element: GModelElement,
        kind: ConditionKind,
        options: ConditionKindOptions
    ): ContextItem | undefined {
        const id = `change-modifier-${element.id}-${kind}`;
        const label = kind === "require" ? "Require" : "Forbid";
        const icon = kind === "require" ? "square-check" : "square-slash";
        const newBlockTarget = kind === "require" ? NEW_REQUIRE_TARGET : NEW_FORBID_TARGET;
        const move = (target: string): ContextItem["action"] =>
            MovePatternElementToConditionOperation.create({ elementId: element.id, target });

        const children: ContextItem[] = options.blocks.map((destination) => ({
            id: `${id}-${destination.target}`,
            label: destination.label,
            icon,
            action: move(destination.target)
        }));
        if (options.canCreate) {
            children.push({ id: `${id}-new`, label: "New Block", icon: "plus", action: move(newBlockTarget) });
        }

        if (children.length === 0) {
            return undefined;
        }
        if (options.blocks.length === 0) {
            return { id, label, icon, action: children[0].action };
        }
        return { id, label, icon, children };
    }

    /**
     * Creates the edit that takes an element out of its block and gives it a modifier.
     *
     * A block may hold neither `create` nor `delete`, so the element cannot stay where it is.
     * It leaves the way a move leaves — with the whole connected component it belongs to, and
     * with the block itself when it was the last member — and the modifier is written onto
     * the element and its adjacent links as it is inserted into the match pattern.
     *
     * @param node The element inside the block
     * @param modifier The modifier it is to carry
     * @returns The workspace edit, or `undefined` when the element cannot leave its block
     */
    private createLeaveConditionEdit(node: AstNode, modifier: PatternModifierKind): WorkspaceEdit | undefined {
        if (
            !this.reflection.isInstance(node, PatternObjectInstance) &&
            !this.reflection.isInstance(node, PatternLink)
        ) {
            return undefined;
        }

        const movable = movableComponent(node, this.reflection);
        const braces = movable == undefined ? undefined : containerBraces(movable.pattern);
        if (movable == undefined || braces == undefined) {
            return undefined;
        }

        const carriers = this.modifierCarriers(node, movable.elements);
        const movedText: string[] = [];
        for (const element of movable.elements) {
            const text = element.$cstNode?.text;
            if (text == undefined || text.length === 0) {
                return undefined;
            }
            movedText.push(carriers.has(element) ? `${modifier as string} ${text}` : text);
        }

        const edits = removedCstNodes(movable.elements, movable.container, this.reflection).map((cstNode) =>
            this.deleteCstNode(cstNode)
        );
        edits.push(this.insertIntoScope(braces.open, braces.close, true, movedText.join("\n")));
        return this.mergeWorkspaceEdits(edits);
    }

    /**
     * Returns the moved elements the modifier is written onto.
     *
     * A node takes its adjacent links with it, the way assigning a modifier to a node already
     * does: a link between a created node and an existing one has to say when it is created.
     * The other nodes of the component travel along unchanged.
     *
     * @param node The element the modifier was requested for
     * @param component The elements moving with it
     * @returns The elements that receive the modifier
     */
    private modifierCarriers(node: AstNode, component: AstNode[]): Set<AstNode> {
        const carriers = new Set<AstNode>([node]);
        if (!this.reflection.isInstance(node, PatternObjectInstance)) {
            return carriers;
        }
        for (const element of component) {
            if (!this.reflection.isInstance(element, PatternLink)) {
                continue;
            }
            const link = element as PatternLinkType;
            if (link.source?.object?.ref === node || link.target?.object?.ref === node) {
                carriers.add(element);
            }
        }
        return carriers;
    }

    /**
     * Checks whether an element is declared inside an application condition block.
     *
     * @param node The AST node to check
     * @returns `true` when the node belongs to a condition block
     */
    private isInsideApplicationCondition(node: { $container?: unknown }): boolean {
        return this.reflection.isInstance(node.$container, PatternApplicationCondition);
    }

    /**
     * Creates a workspace edit that updates the modifier on a pattern object instance.
     * When the modifier is `NONE`, the existing modifier CST node is deleted.
     * Otherwise the modifier keyword is replaced in-place (if one already exists) or a new keyword
     * is inserted before the instance name (if no modifier is present yet).
     * All adjacent `PatternLink` elements — whether they carry an existing modifier or none at all —
     * are updated to the same modifier value so the pattern stays consistent.
     *
     * @param node The PatternObjectInstance AST node
     * @param modifier The new modifier kind to apply
     * @returns The workspace edit, or `undefined` when the change is a no-op or CST nodes cannot
     *          be located
     */
    private async createInstanceModifierEdit(
        node: PatternObjectInstanceType,
        modifier: PatternModifierKind
    ): Promise<WorkspaceEdit | undefined> {
        if (node.$cstNode == undefined) {
            return undefined;
        }

        const edits: WorkspaceEdit[] = [];

        if (modifier === PatternModifierKind.NONE) {
            const instanceModifier = node.modifier;
            if (instanceModifier?.$cstNode == undefined) {
                return undefined;
            }
            edits.push(this.deleteCstNode(instanceModifier.$cstNode));
        } else {
            const modifierText = modifier as string;
            const instanceModifier = node.modifier;

            if (instanceModifier != undefined) {
                const modifierCstNode = GrammarUtils.findNodeForProperty(instanceModifier.$cstNode, "modifier");
                if (modifierCstNode == undefined) {
                    return undefined;
                }
                edits.push(await this.replaceCstNode(modifierCstNode, modifierText));
            } else {
                const nameNode = GrammarUtils.findNodeForProperty(node.$cstNode, "name");
                if (nameNode == undefined) {
                    return undefined;
                }
                const uri = this.getSourceDocument().uri.toString();
                edits.push({ changes: { [uri]: [TextEdit.insert(nameNode.range.start, `${modifierText} `)] } });
            }

            // Propagate to all adjacent links, regardless of whether they already
            // carry a modifier or not — when a node is assigned a modifier for the
            // first time, its links should follow suit.
            const container = node.$container;
            if (container != undefined) {
                const elements = (container as unknown as { elements?: unknown[] }).elements ?? [];
                for (const element of elements) {
                    if (element != undefined && this.reflection.isInstance(element, PatternLink)) {
                        const link = element as PatternLinkType;
                        const sourceRef = link.source?.object?.ref;
                        const targetRef = link.target?.object?.ref;
                        if (sourceRef === node || targetRef === node) {
                            if (link.modifier != undefined && link.modifier.$cstNode != undefined) {
                                // Replace an existing modifier keyword in place.
                                const linkModCstNode = GrammarUtils.findNodeForProperty(
                                    link.modifier.$cstNode,
                                    "modifier"
                                );
                                if (linkModCstNode != undefined) {
                                    edits.push(await this.replaceCstNode(linkModCstNode, modifierText));
                                }
                            } else if (link.$cstNode != undefined) {
                                // Insert a new modifier keyword before the link source.
                                const sourceCstNode = GrammarUtils.findNodeForProperty(link.$cstNode, "source");
                                if (sourceCstNode != undefined) {
                                    const uri = this.getSourceDocument().uri.toString();
                                    edits.push({
                                        changes: {
                                            [uri]: [TextEdit.insert(sourceCstNode.range.start, `${modifierText} `)]
                                        }
                                    });
                                }
                            }
                        }
                    }
                }
            }
        }

        return edits.length > 0 ? this.mergeWorkspaceEdits(edits) : undefined;
    }

    /**
     * Creates a workspace edit that updates the modifier on a pattern link.
     * When the modifier is `NONE`, the existing modifier CST node is deleted.
     * Otherwise the modifier keyword is replaced in-place (if one already exists) or a new keyword
     * is inserted before the link source (if no modifier is present yet).
     *
     * @param node The PatternLink AST node
     * @param modifier The new modifier kind to apply
     * @returns The workspace edit, or `undefined` when the change is a no-op or CST nodes cannot
     *          be located
     */
    private async createLinkModifierEdit(
        node: PatternLinkType,
        modifier: PatternModifierKind
    ): Promise<WorkspaceEdit | undefined> {
        if (node.$cstNode == undefined) {
            return undefined;
        }

        if (modifier === PatternModifierKind.NONE) {
            const modifierNode = node.modifier;
            if (modifierNode?.$cstNode == undefined) {
                return undefined;
            }
            return this.deleteCstNode(modifierNode.$cstNode);
        }

        const modifierText = modifier as string;
        const modifierNode = node.modifier;

        if (modifierNode != undefined) {
            const modifierCstNode = GrammarUtils.findNodeForProperty(modifierNode.$cstNode, "modifier");
            if (modifierCstNode == undefined) {
                return undefined;
            }
            return await this.replaceCstNode(modifierCstNode, modifierText);
        } else {
            const sourceNode = GrammarUtils.findNodeForProperty(node.$cstNode, "source");
            if (sourceNode == undefined) {
                return undefined;
            }
            const uri = this.getSourceDocument().uri.toString();
            return { changes: { [uri]: [TextEdit.insert(sourceNode.range.start, `${modifierText} `)] } };
        }
    }
}
