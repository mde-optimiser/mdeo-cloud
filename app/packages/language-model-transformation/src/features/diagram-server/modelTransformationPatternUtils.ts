import type { AstReflection } from "@mdeo/language-common";
import { PatternModifierKind } from "@mdeo/protocol-model-transformation";
import type { AstNode } from "langium";
import {
    Pattern,
    MatchStatement,
    IfMatchConditionAndBlock,
    WhileMatchStatement,
    UntilMatchStatement,
    ForMatchStatement,
    PatternApplicationCondition,
    PatternModelScope,
    type PatternModelScopeType,
    type PatternType,
    type MatchStatementType,
    type IfMatchConditionAndBlockType,
    type WhileMatchStatementType,
    type UntilMatchStatementType,
    type ForMatchStatementType,
    type PatternApplicationConditionType
} from "../../grammar/modelTransformationTypes.js";

/**
 * Extracts the {@link PatternType} from an AST node returned by
 * {@link GModelIndex.getAstNode} for a match node GModel element.
 *
 * Match node elements are created with different AST node IDs depending on the
 * statement type:
 * - `MatchStatement` — the element ID is the statement's own ID, so
 *   `getAstNode` returns the `MatchStatement` itself.  The pattern is accessed
 *   via `stmt.pattern`.
 * - `IfMatchStatement`, `WhileMatchStatement`, `UntilMatchStatement`,
 *   `ForMatchStatement` — the element ID is the inner `Pattern`'s ID, so
 *   `getAstNode` returns the `Pattern` directly.
 * - `IfMatchConditionAndBlock` — if ever returned directly (unlikely given
 *   current ID generation), its `.pattern` is used.
 *
 * @param astNode An AST node returned by {@link GModelIndex.getAstNode}
 * @param reflection The AST reflection instance for type checks
 * @returns The associated {@link PatternType}, or `undefined` if the node is
 *          not a pattern-bearing match node
 */
export function getPatternFromMatchNode(astNode: AstNode, reflection: AstReflection): PatternType | undefined {
    if (reflection.isInstance(astNode, Pattern)) {
        return astNode as PatternType;
    }

    if (reflection.isInstance(astNode, MatchStatement)) {
        return (astNode as MatchStatementType).pattern;
    }

    if (reflection.isInstance(astNode, IfMatchConditionAndBlock)) {
        return (astNode as IfMatchConditionAndBlockType).pattern;
    }

    if (reflection.isInstance(astNode, WhileMatchStatement)) {
        return (astNode as WhileMatchStatementType).pattern;
    }

    if (reflection.isInstance(astNode, UntilMatchStatement)) {
        return (astNode as UntilMatchStatementType).pattern;
    }

    if (reflection.isInstance(astNode, ForMatchStatement)) {
        return (astNode as ForMatchStatementType).pattern;
    }

    return undefined;
}

/**
 * A pattern element together with the application condition block and the model scope block it
 * was declared in.
 */
export interface FlattenedPatternElement {
    /** The pattern element itself. */
    element: AstNode;
    /** The block the element belongs to, or `undefined` for elements of the match pattern. */
    condition?: PatternApplicationConditionType;
    /** The model scope block (`in donor { … }`) the element belongs to, if any. */
    scope?: PatternModelScopeType;
}

/**
 * Returns every element of a pattern together with the application condition block and the
 * model scope block it belongs to, so that block members are handled like the elements of the
 * match pattern.
 *
 * Blocks are not drawn as containers: following Henshin, their members stay in the
 * match node and are tagged with `«forbid name»` / `«require name»` / `«in donor»` instead,
 * which keeps a condition legible next to the pattern it constrains. Everything that walks a
 * pattern for its diagram elements therefore has to see through the blocks.
 *
 * @param pattern The pattern whose elements should be listed.
 * @param reflection The AST reflection instance for type checks.
 * @returns The elements in declaration order, condition block members after the others.
 */
export function flattenPatternElements(
    pattern: { elements?: unknown[] } | undefined,
    reflection: AstReflection
): FlattenedPatternElement[] {
    const result: FlattenedPatternElement[] = [];
    const conditions: FlattenedPatternElement[] = [];

    const visit = (
        elements: unknown[] | undefined,
        condition: PatternApplicationConditionType | undefined,
        scope: PatternModelScopeType | undefined
    ) => {
        for (const element of elements ?? []) {
            if (reflection.isInstance(element, PatternApplicationCondition)) {
                visit(
                    (element as PatternApplicationConditionType).elements,
                    element as PatternApplicationConditionType,
                    scope
                );
            } else if (reflection.isInstance(element, PatternModelScope)) {
                visit((element as PatternModelScopeType).elements, condition, element as PatternModelScopeType);
            } else {
                (condition != undefined ? conditions : result).push({
                    element: element as AstNode,
                    ...(condition != undefined ? { condition } : {}),
                    ...(scope != undefined ? { scope } : {})
                });
            }
        }
    };
    visit(pattern?.elements, undefined, undefined);

    return [...result, ...conditions];
}

/**
 * Returns the pattern a node belongs to, seeing through application condition blocks.
 *
 * @param node The node to start from.
 * @param reflection The AST reflection instance for type checks.
 * @returns The enclosing pattern, or `undefined` when the node is not inside one.
 */
export function findContainingPattern(node: AstNode, reflection: AstReflection): PatternType | undefined {
    let current: AstNode | undefined = node.$container;
    while (current != undefined) {
        if (reflection.isInstance(current, Pattern)) {
            return current as PatternType;
        }
        current = current.$container;
    }
    return undefined;
}

/**
 * Returns the application condition blocks of a pattern, in declaration order.
 *
 * @param pattern The pattern to inspect.
 * @param reflection The AST reflection instance for type checks.
 * @returns The blocks of the pattern.
 */
export function patternConditions(
    pattern: { elements?: unknown[] } | undefined,
    reflection: AstReflection
): PatternApplicationConditionType[] {
    return (pattern?.elements ?? []).filter((element) =>
        reflection.isInstance(element, PatternApplicationCondition)
    ) as PatternApplicationConditionType[];
}

/**
 * Returns every application condition block of a pattern, those inside its model scope blocks
 * (and the scope blocks inside a condition) included, in declaration order.
 *
 * @param pattern The pattern to inspect.
 * @param reflection The AST reflection instance for type checks.
 * @returns The blocks of the pattern.
 */
export function allPatternConditions(
    pattern: { elements?: unknown[] } | undefined,
    reflection: AstReflection
): PatternApplicationConditionType[] {
    const result: PatternApplicationConditionType[] = [];
    const visit = (elements: unknown[] | undefined) => {
        for (const element of elements ?? []) {
            if (reflection.isInstance(element, PatternApplicationCondition)) {
                result.push(element as PatternApplicationConditionType);
                visit((element as PatternApplicationConditionType).elements);
            } else if (reflection.isInstance(element, PatternModelScope)) {
                visit((element as PatternModelScopeType).elements);
            }
        }
    };
    visit(pattern?.elements);
    return result;
}

/**
 * Returns the model scope blocks (`in donor { … }`) declared directly in a pattern, in
 * declaration order.
 *
 * @param pattern The pattern to inspect.
 * @param reflection The AST reflection instance for type checks.
 * @returns The scope blocks of the pattern.
 */
export function patternModelScopes(
    pattern: { elements?: unknown[] } | undefined,
    reflection: AstReflection
): PatternModelScopeType[] {
    return (pattern?.elements ?? []).filter((element) =>
        reflection.isInstance(element, PatternModelScope)
    ) as PatternModelScopeType[];
}

/**
 * Returns the model scope block a node is declared in.
 *
 * @param node The node to start from.
 * @param reflection The AST reflection instance for type checks.
 * @returns The scope block, or `undefined` when the node belongs to the transformed model.
 */
export function findContainingModelScope(node: AstNode, reflection: AstReflection): PatternModelScopeType | undefined {
    let current: AstNode | undefined = node.$container;
    while (current != undefined) {
        if (reflection.isInstance(current, PatternModelScope)) {
            return current as PatternModelScopeType;
        }
        if (reflection.isInstance(current, Pattern)) {
            return undefined;
        }
        current = current.$container;
    }
    return undefined;
}

/**
 * Returns the name each application condition block of a pattern is shown under.
 *
 * A block does not have to be named: a name is only worth writing when it says
 * something, so the editor leaves new blocks unnamed. An unnamed block is still shown
 * as «forbid 2» rather than as a bare «forbid», because the elements of two blocks sit
 * side by side in the same match node and the reader has nothing else to tell the graph
 * of one block from the graph of the other.
 *
 * The numbers count the unnamed blocks in declaration order and skip every number a
 * named block of the same pattern already occupies, so that a display name always names
 * exactly one block: a block named 2 in the source keeps that name for itself.
 *
 * @param pattern The pattern whose blocks are named.
 * @param reflection The AST reflection instance for type checks.
 * @returns The display name of every block of the pattern.
 */
export function conditionDisplayNames(
    pattern: { elements?: unknown[] } | undefined,
    reflection: AstReflection
): Map<PatternApplicationConditionType, string> {
    const conditions = allPatternConditions(pattern, reflection);
    const taken = new Set(conditions.map((condition) => condition.name).filter((name) => name != undefined));

    const names = new Map<PatternApplicationConditionType, string>();
    let next = 1;
    for (const condition of conditions) {
        if (condition.name != undefined) {
            names.set(condition, condition.name);
            continue;
        }
        while (taken.has(`${next}`)) {
            next++;
        }
        names.set(condition, `${next}`);
        next++;
    }
    return names;
}

/**
 * Returns the name a single application condition block is shown under.
 *
 * @param condition The block.
 * @param reflection The AST reflection instance for type checks.
 * @returns The display name, see {@link conditionDisplayNames}.
 */
export function conditionDisplayName(condition: PatternApplicationConditionType, reflection: AstReflection): string {
    if (condition.name != undefined) {
        return condition.name;
    }
    const names = conditionDisplayNames(findContainingPattern(condition, reflection), reflection);
    return names.get(condition) ?? "1";
}

/**
 * Returns the application condition block a node is declared in.
 *
 * @param node The node to start from.
 * @param reflection The AST reflection instance for type checks.
 * @returns The block, or `undefined` when the node belongs to the match pattern itself.
 */
export function findContainingCondition(
    node: AstNode,
    reflection: AstReflection
): PatternApplicationConditionType | undefined {
    let current: AstNode | undefined = node.$container;
    while (current != undefined) {
        if (reflection.isInstance(current, PatternApplicationCondition)) {
            return current as PatternApplicationConditionType;
        }
        if (reflection.isInstance(current, Pattern)) {
            return undefined;
        }
        current = current.$container;
    }
    return undefined;
}

/**
 * Converts a modifier keyword to the corresponding {@link PatternModifierKind}.
 *
 * @param modifier The raw modifier keyword, if any.
 * @returns The matching kind, {@link PatternModifierKind.NONE} for an unknown or absent keyword.
 */
export function patternModifierKind(modifier: string | undefined): PatternModifierKind {
    switch (modifier) {
        case "create":
            return PatternModifierKind.CREATE;
        case "delete":
            return PatternModifierKind.DELETE;
        case "require":
            return PatternModifierKind.REQUIRE;
        case "forbid":
            return PatternModifierKind.FORBID;
        default:
            return PatternModifierKind.NONE;
    }
}

/**
 * Returns the modifier an element effectively carries.
 *
 * An element declared inside an application condition block carries the block's kind:
 * a block is what `forbid` / `require` element modifiers used to express, so everything
 * that reasons about modifiers - what a link may connect, whether a property may be
 * assigned - has to read the block that way.
 *
 * @param node The element, or a node declared inside one.
 * @param reflection The AST reflection instance for type checks.
 * @returns The effective modifier kind of the element.
 */
export function effectivePatternModifier(node: AstNode, reflection: AstReflection): PatternModifierKind {
    const condition = findContainingCondition(node, reflection);
    if (condition != undefined) {
        return patternModifierKind(condition.kind);
    }
    const modifier = (node as { modifier?: { modifier?: string } }).modifier?.modifier;
    return patternModifierKind(modifier);
}

/**
 * Returns the stereotype text (without guillemets) of an element of the blocks it is declared
 * in: the condition block, named (an unnamed one by its number), and the model scope block, by
 * its model, e.g. `forbid 1`, `in donor` or `forbid 1, in donor`.
 *
 * @param condition The condition block the element belongs to, if any.
 * @param scope The model scope block the element belongs to, if any.
 * @param reflection The AST reflection instance for type checks.
 * @returns The stereotype text, or `undefined` for elements of the match pattern itself.
 */
export function blockStereotype(
    condition: PatternApplicationConditionType | undefined,
    scope: PatternModelScopeType | undefined,
    reflection: AstReflection
): string | undefined {
    const parts: string[] = [];
    if (condition != undefined) {
        parts.push(`${condition.kind ?? "forbid"} ${conditionDisplayName(condition, reflection)}`);
    }
    if (scope != undefined) {
        parts.push(`in ${scope.model?.$refText ?? "?"}`);
    }
    return parts.length > 0 ? parts.join(", ") : undefined;
}
