import type { AstReflection } from "@mdeo/language-common";
import { sharedImport } from "@mdeo/language-shared";
import type { AstNode, CstNode } from "langium";
import {
    Pattern,
    PatternApplicationCondition,
    PatternLink,
    PatternObjectInstance,
    PatternObjectInstanceReference,
    WhereClause,
    expressionTypes,
    type PatternApplicationConditionType,
    type PatternLinkType,
    type PatternObjectInstanceType,
    type PatternPropertyAssignmentType,
    type PatternType
} from "../../grammar/modelTransformationTypes.js";
import { conditionDisplayName, findContainingPattern, patternConditions } from "./modelTransformationPatternUtils.js";

const { AstUtils, GrammarUtils } = sharedImport("langium");

/**
 * The direct container of a pattern element: the match pattern itself, or one of its
 * application condition blocks.
 */
export type PatternElementContainer = PatternType | PatternApplicationConditionType;

/** The kind of an application condition block. */
export type ConditionKind = "forbid" | "require";

/**
 * The elements that travel together when a pattern element is moved.
 *
 * Because a block is a graph of its own, an element never travels alone: the whole
 * connected component it belongs to inside its current container moves with it.
 */
export interface MovableComponent {
    /** The pattern the elements belong to. */
    readonly pattern: PatternType;
    /** The container they are currently declared in. */
    readonly container: PatternElementContainer;
    /** The elements to move, in declaration order. */
    readonly elements: AstNode[];
}

/** A block a pattern element may be moved into. */
export interface ConditionDestination {
    /** The name the block is shown under, see {@link conditionDisplayName}. */
    readonly label: string;
    /** The move target naming the block, see {@link MovePatternElementToConditionOperation}. */
    readonly target: string;
}

/** What a pattern element may do with the blocks of one kind. */
export interface ConditionKindOptions {
    /** The existing blocks of that kind it may be moved into. */
    readonly blocks: ConditionDestination[];
    /** Whether a new block of that kind would say something the source does not say already. */
    readonly canCreate: boolean;
}

/** The destinations a pattern element may be moved to. */
export interface ConditionMoveOptions {
    /** Whether the element is declared inside a block, and so can be moved back out of it. */
    readonly inBlock: boolean;
    /** What it may do with the `forbid` blocks. */
    readonly forbid: ConditionKindOptions;
    /** What it may do with the `require` blocks. */
    readonly require: ConditionKindOptions;
}

/**
 * Collects the elements that would move with the given one, if it can move at all.
 *
 * A move never produces source that the validator would reject, so a component whose
 * names are still read by what stays behind, or that reads a name of the block it would
 * leave, does not move: a block-local name means nothing outside its block.
 *
 * @param astNode The selected element.
 * @param reflection The AST reflection instance for type checks.
 * @returns The movable component, or `undefined` when the element cannot be moved.
 */
export function movableComponent(astNode: AstNode, reflection: AstReflection): MovableComponent | undefined {
    if (
        !reflection.isInstance(astNode, PatternObjectInstance) &&
        !reflection.isInstance(astNode, PatternObjectInstanceReference) &&
        !reflection.isInstance(astNode, PatternLink) &&
        !reflection.isInstance(astNode, WhereClause)
    ) {
        return undefined;
    }

    const pattern = findContainingPattern(astNode, reflection);
    const container = elementContainer(astNode, reflection);
    if (pattern == undefined || container == undefined) {
        return undefined;
    }

    const elements = collectComponent(astNode, container, reflection);
    if (elements.length === 0 || !isSelfContained(elements, reflection)) {
        return undefined;
    }
    if (readsBlockLocalNames(elements, container, reflection)) {
        return undefined;
    }
    return { pattern, container, elements };
}

/**
 * Returns the blocks the given element may be moved to.
 *
 * The blocks of the other kind are listed as well: which block an element belongs to is a
 * question of its own, and a `forbid` block is as reachable from a `require` one as from
 * the match pattern.
 *
 * A new block is worth creating unless the element is already alone in a block of that kind:
 * moving the whole of a block into a fresh one of its own kind would rewrite the source
 * without changing what it says.
 *
 * @param astNode The selected element.
 * @param reflection The AST reflection instance for type checks.
 * @returns The destinations, or `undefined` when the element cannot be moved at all.
 */
export function conditionMoveOptions(astNode: AstNode, reflection: AstReflection): ConditionMoveOptions | undefined {
    const movable = movableComponent(astNode, reflection);
    if (movable == undefined) {
        return undefined;
    }

    const current = reflection.isInstance(movable.container, PatternApplicationCondition)
        ? (movable.container as PatternApplicationConditionType)
        : undefined;
    const currentKind = current == undefined ? undefined : conditionKind(current);
    const wholeBlock = current != undefined && movable.elements.length === (current.elements ?? []).length;
    const canEnterBlock = canEnterCondition(movable.elements, movable.container, reflection);

    const blocks: Record<ConditionKind, ConditionDestination[]> = { forbid: [], require: [] };
    if (canEnterBlock) {
        for (const [index, condition] of patternConditions(movable.pattern, reflection).entries()) {
            if (condition === current) {
                continue;
            }
            blocks[conditionKind(condition)].push({
                label: conditionDisplayName(condition, reflection),
                target: `${index}`
            });
        }
    }

    const optionsFor = (kind: ConditionKind): ConditionKindOptions => ({
        blocks: blocks[kind],
        canCreate: canEnterBlock && !(wholeBlock && currentKind === kind)
    });

    return { inBlock: current != undefined, forbid: optionsFor("forbid"), require: optionsFor("require") };
}

/**
 * Returns the kind of a block, which is `forbid` unless it says otherwise.
 *
 * @param condition The block.
 * @returns Its kind.
 */
function conditionKind(condition: PatternApplicationConditionType): ConditionKind {
    return condition.kind === "require" ? "require" : "forbid";
}

/**
 * Checks whether the component may live inside an application condition block.
 *
 * A block holds a plain sub-graph and the constraints on it: no modifier, since a
 * condition never rewrites the model, no property assignment for the same reason, and only
 * the elements its grammar admits. A component that carries any of those, or whose names
 * are still read by what stays behind, has no valid form inside a block, so the block
 * destinations are not offered for it.
 *
 * @param component The elements about to be moved.
 * @param container The container the component currently lives in.
 * @param reflection The AST reflection instance for type checks.
 * @returns `true` when the component can be moved into a block.
 */
export function canEnterCondition(
    component: AstNode[],
    container: PatternElementContainer,
    reflection: AstReflection
): boolean {
    for (const node of component) {
        if (
            !reflection.isInstance(node, PatternObjectInstance) &&
            !reflection.isInstance(node, PatternObjectInstanceReference) &&
            !reflection.isInstance(node, PatternLink) &&
            !reflection.isInstance(node, WhereClause)
        ) {
            return false;
        }
        const modifier = (node as { modifier?: { modifier?: string } }).modifier?.modifier;
        if (modifier != undefined) {
            return false;
        }
        const properties = (node as { properties?: PatternPropertyAssignmentType[] }).properties ?? [];
        if (properties.some((property) => property.operator === "=")) {
            return false;
        }
    }

    return !leavesReadersBehind(component, container, reflection);
}

/**
 * Returns the direct container of a pattern element: either the pattern itself or the
 * condition block it is declared in.
 *
 * @param node The element.
 * @param reflection The AST reflection instance for type checks.
 * @returns The container, or `undefined` when the node is not a pattern element.
 */
function elementContainer(node: AstNode, reflection: AstReflection): PatternElementContainer | undefined {
    const container = node.$container;
    if (container == undefined) {
        return undefined;
    }
    if (reflection.isInstance(container, Pattern) || reflection.isInstance(container, PatternApplicationCondition)) {
        return container as PatternElementContainer;
    }
    return undefined;
}

/**
 * Collects the connected component of [start] inside its container.
 *
 * Instances are connected when a link of the same container joins them; a link belongs to
 * the component of its endpoints. A link whose endpoints both live outside the container
 * (an anchor-only link) forms a component on its own.
 *
 * A reference constrains a node of the enclosing pattern and a where clause constrains the
 * graph as a whole; neither is part of the graph, so both move on their own.
 *
 * @param start The selected element.
 * @param container The container the element is declared in.
 * @param reflection The AST reflection instance for type checks.
 * @returns The elements to move, in declaration order.
 */
function collectComponent(start: AstNode, container: PatternElementContainer, reflection: AstReflection): AstNode[] {
    const elements = (container.elements ?? []) as AstNode[];
    const localInstances = new Set<PatternObjectInstanceType>();
    for (const element of elements) {
        if (reflection.isInstance(element, PatternObjectInstance)) {
            localInstances.add(element as PatternObjectInstanceType);
        }
    }

    const reachedInstances = new Set<PatternObjectInstanceType>();
    const reachedLinks = new Set<PatternLinkType>();

    if (reflection.isInstance(start, PatternObjectInstance)) {
        reachedInstances.add(start as PatternObjectInstanceType);
    } else if (reflection.isInstance(start, PatternLink)) {
        const link = start as PatternLinkType;
        reachedLinks.add(link);
        for (const endpoint of endpointsOf(link)) {
            if (localInstances.has(endpoint)) {
                reachedInstances.add(endpoint);
            }
        }
    } else {
        return [start];
    }

    let changed = true;
    while (changed) {
        changed = false;
        for (const element of elements) {
            if (!reflection.isInstance(element, PatternLink)) {
                continue;
            }
            const link = element as PatternLinkType;
            if (reachedLinks.has(link)) {
                continue;
            }
            const endpoints = endpointsOf(link).filter((endpoint) => localInstances.has(endpoint));
            if (!endpoints.some((endpoint) => reachedInstances.has(endpoint))) {
                continue;
            }
            reachedLinks.add(link);
            for (const endpoint of endpoints) {
                reachedInstances.add(endpoint);
            }
            changed = true;
        }
    }

    return elements.filter(
        (element) =>
            reachedInstances.has(element as PatternObjectInstanceType) || reachedLinks.has(element as PatternLinkType)
    );
}

/**
 * Returns the resolved endpoint instances of a link.
 *
 * @param link The link.
 * @returns The resolved endpoints, skipping unresolved references.
 */
function endpointsOf(link: PatternLinkType): PatternObjectInstanceType[] {
    const endpoints: PatternObjectInstanceType[] = [];
    const source = link.source?.object?.ref as PatternObjectInstanceType | undefined;
    const target = link.target?.object?.ref as PatternObjectInstanceType | undefined;
    if (source != undefined) {
        endpoints.push(source);
    }
    if (target != undefined) {
        endpoints.push(target);
    }
    return endpoints;
}

/**
 * Checks that nothing outside the moved component refers to one of its instances.
 *
 * A block-local node is invisible outside its block, so a move that leaves such a
 * reference behind would turn a valid transformation into an unresolvable one. Rather
 * than producing broken source, the move is not offered at all in that case.
 *
 * @param component The elements about to be moved.
 * @param reflection The AST reflection instance for type checks.
 * @returns `true` when the move keeps every reference resolvable.
 */
function isSelfContained(component: AstNode[], reflection: AstReflection): boolean {
    const moved = new Set(component);
    const movedInstances = component.filter((node) =>
        reflection.isInstance(node, PatternObjectInstance)
    ) as PatternObjectInstanceType[];
    if (movedInstances.length === 0) {
        return true;
    }

    const root = AstUtils.getDocument(component[0]).parseResult.value;
    for (const node of AstUtils.streamAllContents(root)) {
        if (moved.has(node)) {
            continue;
        }
        for (const info of AstUtils.streamReferences(node)) {
            const reference = info.reference as { ref?: AstNode };
            const target = reference.ref;
            if (
                target != undefined &&
                movedInstances.includes(target as PatternObjectInstanceType) &&
                !isInside(node, moved)
            ) {
                return false;
            }
        }
    }
    return true;
}

/**
 * Checks whether the moved component reads a name that only exists in its current block.
 *
 * A where clause such as `where s.duration > 30` is meaningful only where `s` is
 * declared. Moving it out of the block that declares `s` would leave an unresolvable
 * reference behind, so such a move is refused rather than performed and then flagged.
 *
 * Identifiers in expressions are resolved by the type system rather than by a Langium
 * cross-reference, so they are matched against the block by name.
 *
 * @param component The elements about to be moved.
 * @param container The container the component currently lives in.
 * @param reflection The AST reflection instance for type checks.
 * @returns `true` when the component depends on a node declared in its current block.
 */
function readsBlockLocalNames(
    component: AstNode[],
    container: PatternElementContainer,
    reflection: AstReflection
): boolean {
    if (!reflection.isInstance(container, PatternApplicationCondition)) {
        return false;
    }

    const blockInstances = new Set<AstNode>(
        ((container as PatternApplicationConditionType).elements ?? []).filter((element) =>
            reflection.isInstance(element, PatternObjectInstance)
        ) as AstNode[]
    );
    const moved = new Set(component);

    for (const node of component) {
        for (const contained of [node, ...AstUtils.streamAllContents(node)]) {
            for (const info of AstUtils.streamReferences(contained)) {
                const target = (info.reference as { ref?: AstNode }).ref;
                if (target != undefined && blockInstances.has(target) && !moved.has(target)) {
                    return true;
                }
            }
        }
    }

    const stayingNames = new Set(
        [...blockInstances]
            .filter((instance) => !moved.has(instance))
            .map((instance) => (instance as PatternObjectInstanceType).name)
            .filter((name): name is string => name != undefined)
    );
    return component.some((node) => readsAnyName(node, stayingNames, reflection));
}

/**
 * Checks whether anything staying behind still reads a name the component takes with it.
 *
 * The names of a block belong to the block alone, so a component that moves into one takes
 * its names out of sight of everything it leaves behind. A property constraint or a where
 * clause that still reads such a name would be left unresolvable, so the move is refused
 * instead.
 *
 * @param component The elements about to be moved.
 * @param container The container the component currently lives in.
 * @param reflection The AST reflection instance for type checks.
 * @returns `true` when an element staying behind reads a name of the component.
 */
function leavesReadersBehind(
    component: AstNode[],
    container: PatternElementContainer,
    reflection: AstReflection
): boolean {
    const movedNames = new Set(
        component
            .filter((node) => reflection.isInstance(node, PatternObjectInstance))
            .map((node) => (node as PatternObjectInstanceType).name)
            .filter((name): name is string => name != undefined)
    );
    if (movedNames.size === 0) {
        return false;
    }

    const moved = new Set(component);
    return ((container.elements ?? []) as AstNode[]).some(
        (element) => !moved.has(element) && readsAnyName(element, movedNames, reflection)
    );
}

/**
 * Checks whether a node reads one of the given names in one of its expressions.
 *
 * @param node The node to scan, including everything nested inside it.
 * @param names The names to look for.
 * @param reflection The AST reflection instance for type checks.
 * @returns `true` when an identifier expression below the node carries one of the names.
 */
function readsAnyName(node: AstNode, names: Set<string>, reflection: AstReflection): boolean {
    if (names.size === 0) {
        return false;
    }
    for (const contained of [node, ...AstUtils.streamAllContents(node)]) {
        if (
            reflection.isInstance(contained, expressionTypes.identifierExpressionType) &&
            names.has((contained as { name?: string }).name ?? "")
        ) {
            return true;
        }
    }
    return false;
}

/**
 * Checks whether a node is contained in one of the moved elements.
 *
 * @param node The node to check.
 * @param moved The moved elements.
 * @returns `true` when the node is one of them or nested inside one.
 */
function isInside(node: AstNode, moved: Set<AstNode>): boolean {
    let current: AstNode | undefined = node;
    while (current != undefined) {
        if (moved.has(current)) {
            return true;
        }
        current = current.$container;
    }
    return false;
}

/**
 * Returns the CST nodes to delete when a component leaves its container.
 *
 * A block that loses its last member is removed with it: an empty block is rejected by the
 * validator, and a condition that constrains nothing has nothing left to say either.
 *
 * @param component The elements about to be moved.
 * @param container The container the component currently lives in.
 * @param reflection The AST reflection instance for type checks.
 * @returns The nodes whose text has to go.
 */
export function removedCstNodes(
    component: AstNode[],
    container: PatternElementContainer,
    reflection: AstReflection
): CstNode[] {
    const moved = new Set(component);
    const staying = ((container.elements ?? []) as AstNode[]).filter((element) => !moved.has(element));
    const containerCstNode = (container as AstNode).$cstNode;
    if (
        staying.length === 0 &&
        reflection.isInstance(container, PatternApplicationCondition) &&
        containerCstNode != undefined
    ) {
        return [containerCstNode];
    }

    return component.map((node) => node.$cstNode).filter((cstNode): cstNode is CstNode => cstNode != undefined);
}

/**
 * Returns the braces enclosing the body of a pattern or a block, which is where an element
 * moved into it is written.
 *
 * @param container The pattern or block.
 * @returns Its braces, or `undefined` when it has no CST node or is not fully parsed.
 */
export function containerBraces(container: PatternElementContainer): { open: CstNode; close: CstNode } | undefined {
    const cstNode = container.$cstNode;
    if (cstNode == undefined) {
        return undefined;
    }
    const open = GrammarUtils.findNodeForKeyword(cstNode, "{");
    const close = GrammarUtils.findNodeForKeyword(cstNode, "}");
    return open != undefined && close != undefined ? { open, close } : undefined;
}
