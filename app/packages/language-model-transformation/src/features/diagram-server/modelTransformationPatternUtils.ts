import type { AstReflection } from "@mdeo/language-common";
import type { AstNode } from "langium";
import {
    Pattern,
    MatchStatement,
    IfMatchConditionAndBlock,
    WhileMatchStatement,
    UntilMatchStatement,
    ForMatchStatement,
    type PatternType,
    type MatchStatementType,
    type IfMatchConditionAndBlockType,
    type WhileMatchStatementType,
    type UntilMatchStatementType,
    type ForMatchStatementType
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
