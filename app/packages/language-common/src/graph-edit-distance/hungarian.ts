import munkres from "munkres";

/**
 * Hungarian Algorithm (Munkres) for Linear Sum Assignment Problem
 *
 * Given an n x n cost matrix, finds the assignment of rows to columns
 * that minimizes the total cost.
 *
 * Returns [rowIndices, colIndices] where rowIndices[i] is assigned to colIndices[i]
 *
 * This is a wrapper around the munkres library.
 *
 * @param costMatrix The n x n cost matrix
 * @returns A tuple of two arrays: row indices and corresponding column indices
 */
export function linearSumAssignment(costMatrix: number[][]): [number[], number[]] {
    const n = costMatrix.length;
    if (n === 0) {
        return [[], []];
    }

    const m = costMatrix[0].length;
    if (n !== m) {
        throw new Error("Cost matrix must be square");
    }

    if (n === 1) {
        return [[0], [0]];
    }

    const pairs = munkres(costMatrix);

    pairs.sort((a, b) => a[0] - b[0]);

    return [pairs.map((p) => p[0]), pairs.map((p) => p[1])];
}
