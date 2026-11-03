import type { AstReflection } from "@mdeo/language-common";
import { sharedImport } from "@mdeo/language-shared";
import type { AstNode, CstNode } from "langium";
import {
    AssignmentStatement,
    CallArgument,
    ForInStatement,
    ModelTransformation,
    Pattern,
    PatternApplicationCondition,
    PatternCollectionAppend,
    PatternLink,
    PatternModelScope,
    PatternObjectInstance,
    PatternObjectInstanceReference,
    PatternVariableReassignment,
    WhereClause,
    expressionTypes,
    type ForInStatementType,
    type ModelTransformationType,
    type PatternApplicationConditionType,
    type PatternLinkType,
    type PatternModelScopeType,
    type PatternObjectInstanceReferenceType,
    type PatternObjectInstanceType,
    type PatternPropertyAssignmentType,
    type PatternType
} from "../../grammar/modelTransformationTypes.js";
import {
    conditionDisplayName,
    findContainingModelScope,
    findContainingPattern,
    patternConditions,
    patternModelScopes
} from "./modelTransformationPatternUtils.js";
import {
    UNRESOLVED_MODEL,
    declarationModel,
    getModelShape,
    type ModelIdentity
} from "../transformationDeclarations.js";
import { MODEL_BLOCK_TARGET_PREFIX, NEW_MODEL_BLOCK_TARGET_PREFIX } from "@mdeo/protocol-model-transformation";

const { AstUtils, GrammarUtils } = sharedImport("langium");

/**
 * The direct container of a pattern element: the match pattern itself, one of its
 * application condition blocks, or one of its model scope blocks.
 */
export type PatternElementContainer = PatternType | PatternApplicationConditionType | PatternModelScopeType;

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
    // the condition blocks of the pattern are graphs of the transformed model; an element of an
    // input model moves between model scope blocks only, see modelMoveOptions
    if (findContainingModelScope(astNode, reflection) != undefined) {
        const none: ConditionKindOptions = { blocks: [], canCreate: false };
        return { inBlock: false, forbid: none, require: none };
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
    if (
        reflection.isInstance(container, Pattern) ||
        reflection.isInstance(container, PatternApplicationCondition) ||
        reflection.isInstance(container, PatternModelScope)
    ) {
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
 * validator, and a condition that constrains nothing has nothing left to say either. The same
 * holds for a model scope block.
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
        (reflection.isInstance(container, PatternApplicationCondition) ||
            reflection.isInstance(container, PatternModelScope)) &&
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

/** A model scope block destination of a pattern element. */
export interface ModelDestination {
    /** The name of the model the destination belongs to. */
    readonly model: string;
    /** The move target naming the block, see {@link MovePatternElementToConditionOperation}. */
    readonly target: string;
}

/** The model scope block destinations a pattern element may be moved to. */
export interface ModelMoveOptions {
    /** Whether the element is declared in a scope block and can be moved back to the transformed model. */
    readonly canLeave: boolean;
    /** The existing scope blocks of the pattern it may be moved into. */
    readonly blocks: ModelDestination[];
    /** The models a new scope block may be created for. */
    readonly newBlocks: ModelDestination[];
}

/**
 * Returns the model scope blocks (`in donor { … }`) the given element may be moved to, and
 * whether it may leave its scope block for the transformed model — the "Move to Block" of
 * input models.
 *
 * Only an element of the match pattern itself or of a scope block moves this way (not one of a
 * condition block). Like the moves between condition blocks, a move never produces source the
 * validator rejects, see {@link canEnterModel}.
 *
 * @param astNode The selected element.
 * @param reflection The AST reflection instance for type checks.
 * @returns The destinations, or `undefined` when the element cannot be moved at all.
 */
export function modelMoveOptions(astNode: AstNode, reflection: AstReflection): ModelMoveOptions | undefined {
    const movable = movableComponent(astNode, reflection);
    if (movable == undefined || reflection.isInstance(movable.container, PatternApplicationCondition)) {
        return undefined;
    }
    const current = reflection.isInstance(movable.container, PatternModelScope)
        ? (movable.container as PatternModelScopeType)
        : undefined;
    const wholeBlock = current != undefined && movable.elements.length === (current.elements ?? []).length;

    const blocks: ModelDestination[] = [];
    for (const [index, scope] of patternModelScopes(movable.pattern, reflection).entries()) {
        const model = scope.model?.ref;
        if (scope === current || model == undefined) {
            continue;
        }
        if (canEnterModel(movable.elements, model, reflection)) {
            blocks.push({ model: scope.model.$refText, target: `${MODEL_BLOCK_TARGET_PREFIX}${index}` });
        }
    }

    const newBlocks: ModelDestination[] = [];
    for (const model of visibleModelValues(movable.pattern, reflection)) {
        if (wholeBlock && current?.model?.ref === model) {
            continue;
        }
        if (canEnterModel(movable.elements, model, reflection)) {
            const name = (model as unknown as { name: string }).name;
            newBlocks.push({ model: name, target: `${NEW_MODEL_BLOCK_TARGET_PREFIX}${name}` });
        }
    }

    return {
        canLeave: current != undefined && canEnterModel(movable.elements, undefined, reflection),
        blocks,
        newBlocks
    };
}

/**
 * Returns the model values a scope block of a pattern can name: the `model` inputs of the
 * transformation and the variables of the enclosing `for … in` loops over `List<model>` inputs.
 *
 * @param pattern The pattern.
 * @param reflection The AST reflection instance for type checks.
 * @returns The declarations of the model values, innermost loop variables first.
 */
export function visibleModelValues(pattern: PatternType, reflection: AstReflection): AstNode[] {
    const result: AstNode[] = [];
    for (let node: AstNode | undefined = pattern.$container; node != undefined; node = node.$container) {
        if (reflection.isInstance(node, ForInStatement)) {
            const loop = node as ForInStatementType;
            const collection = loop.collection as { name?: string } | undefined;
            const root = AstUtils.getContainerOfType(loop, (n) => reflection.isInstance(n, ModelTransformation)) as
                ModelTransformationType | undefined;
            const list = (root?.parameters ?? []).find((p) => p.name === collection?.name);
            if (loop.variable != undefined && list != undefined && getModelShape(reflection, list) === "list") {
                result.push(loop.variable);
            }
        }
        if (reflection.isInstance(node, ModelTransformation)) {
            for (const parameter of (node as ModelTransformationType).parameters ?? []) {
                if (parameter.direction === "in" && getModelShape(reflection, parameter) === "single") {
                    result.push(parameter);
                }
            }
        }
    }
    return result;
}

/**
 * Checks whether the component may live in the given model: inside a scope block of an input
 * model, or (for `undefined`) in the transformed model.
 *
 * An input model is read-only, so a component entering one carries no modifier and assigns no
 * property. Every node the component links to or references without moving it has to be in
 * the destination model as well, since links never cross models. And the objects of an input
 * model are never passed on as elements of the transformed model, so a component whose objects
 * are passed to a call, assigned to an output or looped over does not enter one.
 *
 * @param component The elements about to be moved.
 * @param model The declaration of the destination model value, or `undefined` for the
 *   transformed model.
 * @param reflection The AST reflection instance for type checks.
 * @returns `true` when the component can be moved into that model.
 */
export function canEnterModel(component: AstNode[], model: AstNode | undefined, reflection: AstReflection): boolean {
    const moved = new Set(component);
    const sameModel = (declaration: AstNode | undefined) => {
        if (declaration == undefined || moved.has(declaration)) {
            return true;
        }
        const declared: ModelIdentity = declarationModel(reflection, declaration);
        return declared !== UNRESOLVED_MODEL && declared === model;
    };
    for (const node of component) {
        if (
            !reflection.isInstance(node, PatternObjectInstance) &&
            !reflection.isInstance(node, PatternObjectInstanceReference) &&
            !reflection.isInstance(node, PatternLink) &&
            !reflection.isInstance(node, WhereClause)
        ) {
            return false;
        }
        if (model != undefined) {
            if ((node as { modifier?: { modifier?: string } }).modifier?.modifier != undefined) {
                return false;
            }
            const properties = (node as { properties?: PatternPropertyAssignmentType[] }).properties ?? [];
            if (properties.some((property) => property.operator === "=")) {
                return false;
            }
        }
        if (reflection.isInstance(node, PatternLink)) {
            const link = node as PatternLinkType;
            if (!sameModel(link.source?.object?.ref) || !sameModel(link.target?.object?.ref)) {
                return false;
            }
        }
        if (
            reflection.isInstance(node, PatternObjectInstanceReference) &&
            !sameModel((node as PatternObjectInstanceReferenceType).instance?.ref)
        ) {
            return false;
        }
    }
    return model == undefined || !passesOnMovedObjects(component, reflection);
}

/**
 * Checks whether an object of the component is passed on as an element of the transformed
 * model: as a call argument, as the value of an assignment, or in the collection of a
 * `for … in` loop.
 */
function passesOnMovedObjects(component: AstNode[], reflection: AstReflection): boolean {
    const names = new Set(
        component
            .filter((node) => reflection.isInstance(node, PatternObjectInstance))
            .map((node) => (node as PatternObjectInstanceType).name)
            .filter((name): name is string => name != undefined)
    );
    if (names.size === 0) {
        return false;
    }
    const root = AstUtils.getDocument(component[0]).parseResult.value;
    const isMovedName = (value: AstNode | undefined) =>
        value != undefined &&
        reflection.isInstance(value, expressionTypes.identifierExpressionType) &&
        names.has((value as { name?: string }).name ?? "");
    for (const node of AstUtils.streamAllContents(root)) {
        if (
            reflection.isInstance(node, CallArgument) ||
            reflection.isInstance(node, AssignmentStatement) ||
            reflection.isInstance(node, PatternVariableReassignment) ||
            reflection.isInstance(node, PatternCollectionAppend)
        ) {
            if (isMovedName((node as { value?: AstNode }).value)) {
                return true;
            }
        } else if (reflection.isInstance(node, ForInStatement)) {
            const collection = (node as ForInStatementType).collection;
            if (collection != undefined && readsAnyName(collection, names, reflection)) {
                return true;
            }
        }
    }
    return false;
}
