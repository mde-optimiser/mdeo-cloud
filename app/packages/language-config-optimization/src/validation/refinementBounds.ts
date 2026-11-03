import type { RangeMultiplicityType, SingleMultiplicityType } from "../grammar/optimizationTypes.js";

/**
 * Lower and upper bound of a multiplicity, -1 standing for an unbounded upper bound.
 */
export interface Bounds {
    lower: number;
    upper: number;
}

/**
 * Lower and upper bound of a multiplicity of the metamodel or config language. An omitted
 * multiplicity means exactly one.
 *
 * @param multiplicity The multiplicity node, if any
 * @returns The bounds
 */
export function multiplicityBounds(multiplicity: { $type: string } | undefined): Bounds {
    if (multiplicity == undefined) {
        return { lower: 1, upper: 1 };
    }
    if (multiplicity.$type.includes("Range")) {
        const range = multiplicity as unknown as RangeMultiplicityType;
        return { lower: range.lower, upper: range.upper === "*" ? -1 : (range.upperNumeric ?? 0) };
    }
    const single = multiplicity as unknown as SingleMultiplicityType;
    switch (single.value) {
        case "*":
            return { lower: 0, upper: -1 };
        case "+":
            return { lower: 1, upper: -1 };
        case "?":
            return { lower: 0, upper: 1 };
        default:
            return { lower: single.numericValue ?? 1, upper: single.numericValue ?? 1 };
    }
}

/**
 * Formats bounds as `[lower..upper]`.
 *
 * @param bounds The bounds to format
 * @returns The formatted bounds
 */
export function formatBounds(bounds: Bounds): string {
    return `[${bounds.lower}..${bounds.upper === -1 ? "*" : bounds.upper}]`;
}

/**
 * What is wrong with refining an end declared with [declared] bounds to [refined] ones, as the
 * optimizer checks it: a refined end must allow a link, and may only tighten the bounds.
 *
 * @param refined The bounds the refinement gives
 * @param declared The bounds the metamodel declares
 * @returns The problem, or `undefined` when the refinement is valid
 */
export function refinementBoundsProblem(refined: Bounds, declared: Bounds): string | undefined {
    if (refined.upper === 0) {
        return "A refined end must allow at least one link.";
    }
    const withinLower = refined.lower >= declared.lower;
    const withinUpper = declared.upper === -1 || (refined.upper !== -1 && refined.upper <= declared.upper);
    if (!withinLower || !withinUpper) {
        return `A refinement can only tighten bounds: ${formatBounds(refined)} does not lie within the metamodel's ${formatBounds(declared)}.`;
    }
    return undefined;
}

/**
 * The positions of refinements that name an end an earlier one already refines.
 *
 * @param ends The refined ends, as `Class.end`, in the order the refinements are written
 * @returns The positions of the repeated ones
 */
export function repeatedRefinements(ends: string[]): number[] {
    const seen = new Set<string>();
    const repeated: number[] = [];
    ends.forEach((end, i) => {
        if (seen.has(end)) {
            repeated.push(i);
        }
        seen.add(end);
    });
    return repeated;
}
