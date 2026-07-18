/**
 * Shared parsing utilities for model transformation label editing.
 * Used by both the apply-label-edit handler and the label-edit validator.
 */

import type { PatternPropertyOperator } from "../../plugin/typedAst.js";

/**
 * Parses an instance label in `name : type` format.
 *
 * @param label The label text to parse
 * @returns The parsed name and type, or undefined if the format is invalid
 */
export function parseInstanceLabel(label: string): { name: string; type: string } | undefined {
    const colonIndex = label.lastIndexOf(":");
    if (colonIndex === -1) {
        return undefined;
    }
    const name = label.substring(0, colonIndex).trim();
    const type = label.substring(colonIndex + 1).trim();
    return name.length > 0 && type.length > 0 ? { name, type } : undefined;
}

/**
 * Finds the index of the assignment `=` operator in a string,
 * skipping over multi-character operators (`==`, `!=`, `<=`, `>=`).
 *
 * @param text The text to search
 * @returns The index of the single `=`, or -1 if not found
 */
export function findAssignmentIndex(text: string): number {
    for (let i = 0; i < text.length; i++) {
        const ch = text[i];
        // Skip `!=`, `<=`, `>=` — the `=` here belongs to the multi-char operator.
        if ((ch === "!" || ch === "<" || ch === ">") && text[i + 1] === "=") {
            i++; // skip the `=` that follows
            continue;
        }
        if (ch === "=") {
            // Skip `==`.
            if (text[i + 1] === "=") {
                i++;
                continue;
            }
            return i;
        }
    }
    return -1;
}

/**
 * Parses a variable label in `var name[: type] = expr` format.
 * The optional type annotation is preserved in the output for round-tripping,
 * but the type itself is not validated or edited beyond being passed through.
 *
 * @param label The label text to parse
 * @returns The parsed name and value expression, or undefined if the format is invalid
 */
export function parseVariableLabel(label: string): { name: string; value: string } | undefined {
    if (!label.startsWith("var ")) {
        return undefined;
    }
    const rest = label.substring(4).trim(); // strip "var "
    const eqIndex = findAssignmentIndex(rest);
    if (eqIndex === -1) {
        return undefined;
    }
    // The portion before `=` may be `name` or `name: type` — extract only the name.
    const beforeEq = rest.substring(0, eqIndex).trim();
    const colonIndex = beforeEq.indexOf(":");
    const name = (colonIndex !== -1 ? beforeEq.substring(0, colonIndex) : beforeEq).trim();
    const value = rest.substring(eqIndex + 1).trim();
    return name.length > 0 && value.length > 0 ? { name, value } : undefined;
}

/**
 * Parses a variable reassignment label in `name = expr` format.
 *
 * Unlike {@link parseVariableLabel}, a reassignment has no `var` keyword and no type
 * annotation: the name refers to an already-declared variable and only the assigned
 * expression is edited. A leading `var ` is rejected so that declarations are not mistaken
 * for reassignments.
 *
 * @param label The label text to parse
 * @returns The parsed name and value expression, or undefined if the format is invalid
 */
export function parseVariableReassignmentLabel(label: string): { name: string; value: string } | undefined {
    if (label.startsWith("var ")) {
        return undefined;
    }
    const eqIndex = findAssignmentIndex(label);
    if (eqIndex === -1) {
        return undefined;
    }
    const name = label.substring(0, eqIndex).trim();
    const value = label.substring(eqIndex + 1).trim();
    return name.length > 0 && value.length > 0 ? { name, value } : undefined;
}

/**
 * All multi-character comparison operators, checked longest-first so that
 * `<=` is found before a bare `<`, etc.
 */
const MULTI_CHAR_OPERATORS: PatternPropertyOperator[] = ["==", "!=", "<=", ">="];

/**
 * All single-character operators.
 */
const SINGLE_CHAR_OPERATORS: PatternPropertyOperator[] = ["<", ">", "="];

/**
 * Parses a property assignment label into its three parts.
 *
 * Supports all property operators: `=`, `==`, `!=`, `<`, `>`, `<=`, `>=`.
 * Multi-character operators are checked before single-character ones to avoid
 * prefix mismatches (e.g. `<=` found before `<`).
 *
 * @param label The raw label text to parse
 * @returns The parsed `{ name, operator, value }`, or an error message string if parsing fails
 */
export function parseModelTransformationPropertyLabel(
    label: string
): { name: string; operator: PatternPropertyOperator; value: string } | string {
    // Try multi-character operators first (longest-match).
    for (const op of MULTI_CHAR_OPERATORS) {
        const idx = label.indexOf(op);
        if (idx === -1) continue;
        const name = label.substring(0, idx).trim();
        const value = label.substring(idx + op.length).trim();
        if (name.length === 0) {
            return "Missing property name before operator.";
        }
        if (value.length === 0) {
            return "Missing value expression after operator.";
        }
        return { name, operator: op, value };
    }

    // Fall back to single-character operators.
    for (const op of SINGLE_CHAR_OPERATORS) {
        const idx = label.indexOf(op);
        if (idx === -1) continue;
        const name = label.substring(0, idx).trim();
        const value = label.substring(idx + op.length).trim();
        if (name.length === 0) {
            return "Missing property name before operator.";
        }
        if (value.length === 0) {
            return "Missing value expression after operator.";
        }
        return { name, operator: op, value };
    }

    return "Missing operator in property label.";
}

/**
 * Extracts the expression text from a where clause label.
 * Expected format: `where <expression>`
 *
 * @param label The full label text
 * @returns The expression string, or undefined if the prefix is missing
 */
export function extractWhereClauseExpression(label: string): string | undefined {
    const prefix = "where ";
    if (!label.startsWith(prefix)) {
        return undefined;
    }
    return label.substring(prefix.length).trim();
}
