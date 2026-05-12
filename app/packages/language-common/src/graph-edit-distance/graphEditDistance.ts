/**
 * Graph Edit Distance implementation
 * Aligned with NetworkX's similarity.py
 */

import { linearSumAssignment } from "./hungarian.js";
import type { MultiGraph, NodeId, EdgeKey, NodeAttributes, EdgeAttributes } from "./multiGraph.js";

// Type definitions for cost functions
export type NodeSubstCost = (attrs1: NodeAttributes, attrs2: NodeAttributes) => number;
export type NodeDelCost = (attrs: NodeAttributes) => number;
export type NodeInsCost = (attrs: NodeAttributes) => number;
export type EdgeSubstCost = (attrs1: EdgeAttributes, attrs2: EdgeAttributes) => number;
export type EdgeDelCost = (attrs: EdgeAttributes) => number;
export type EdgeInsCost = (attrs: EdgeAttributes) => number;

// Edge tuple type: (source, target, key)
export type EdgeTuple = [NodeId, NodeId, EdgeKey];

// Edit path types
export type NodeEditPath = Array<[NodeId | null, NodeId | null]>;
export type EdgeEditPath = Array<[EdgeTuple | null, EdgeTuple | null]>;

export interface GEDOptions {
    nodeSubstCost?: NodeSubstCost;
    nodeDelCost?: NodeDelCost;
    nodeInsCost?: NodeInsCost;
    edgeSubstCost?: EdgeSubstCost;
    edgeDelCost?: EdgeDelCost;
    edgeInsCost?: EdgeInsCost;
    upperBound?: number;
}

/**
 * CostMatrix structure to hold cost matrix and LSA solution
 */
interface CostMatrix {
    C: number[][];
    lsaRowInd: number[];
    lsaColInd: number[];
    ls: number; // lower bound sum
}

/**
 * Create a CostMatrix with LSA solution
 */
function makeCostMatrix(C: number[][], m: number, n: number): CostMatrix {
    const size = m + n;

    if (size === 0) {
        return { C, lsaRowInd: [], lsaColInd: [], ls: 0 };
    }

    const [lsaRowInd, lsaColInd] = linearSumAssignment(C);

    // Fixup dummy assignments:
    // each substitution i<->j should have dummy assignment m+j<->n+i
    const newLsaRowInd = [...lsaRowInd];
    const newLsaColInd = [...lsaColInd];

    for (let idx = 0; idx < lsaRowInd.length; idx++) {
        const i = lsaRowInd[idx];
        const j = lsaColInd[idx];

        // Check if this is a substitution (both < their respective limits)
        if (i < m && j < n) {
            // Find the dummy entry that should correspond to this
            // Dummy should be at row m+j, col n+i
            for (let dummyIdx = 0; dummyIdx < lsaRowInd.length; dummyIdx++) {
                if (lsaRowInd[dummyIdx] >= m && lsaColInd[dummyIdx] >= n) {
                    // This is a dummy assignment, fix it
                    const expectedRow = m + j;
                    const expectedCol = n + i;
                    if (newLsaRowInd[dummyIdx] !== expectedRow || newLsaColInd[dummyIdx] !== expectedCol) {
                        // Find if we need to swap
                        for (let k = 0; k < lsaRowInd.length; k++) {
                            if (k !== dummyIdx && newLsaRowInd[k] === expectedRow) {
                                // Swap col assignments
                                const tmp = newLsaColInd[k];
                                newLsaColInd[k] = newLsaColInd[dummyIdx];
                                newLsaColInd[dummyIdx] = tmp;
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    // Actually, let's follow the Python approach more closely
    // Reset and do proper fixup
    const finalLsaRowInd = [...lsaRowInd];
    const finalLsaColInd = [...lsaColInd];

    // Find substitution pairs and fix their dummies
    for (let idx = 0; idx < lsaRowInd.length; idx++) {
        const i = lsaRowInd[idx];
        const j = lsaColInd[idx];

        if (i < m && j < n) {
            // This is a substitution, find and fix dummy
            const dummyRow = m + j;
            const dummyCol = n + i;

            // Find where dummyRow is currently assigned
            let dummyIdx = -1;
            for (let k = 0; k < finalLsaRowInd.length; k++) {
                if (finalLsaRowInd[k] === dummyRow) {
                    dummyIdx = k;
                    break;
                }
            }

            if (dummyIdx !== -1 && finalLsaColInd[dummyIdx] !== dummyCol) {
                // Need to swap - find where dummyCol is currently assigned
                let colIdx = -1;
                for (let k = 0; k < finalLsaColInd.length; k++) {
                    if (finalLsaColInd[k] === dummyCol) {
                        colIdx = k;
                        break;
                    }
                }

                if (colIdx !== -1) {
                    // Swap column assignments
                    const tmp = finalLsaColInd[dummyIdx];
                    finalLsaColInd[dummyIdx] = finalLsaColInd[colIdx];
                    finalLsaColInd[colIdx] = tmp;
                }
            }
        }
    }

    const ls = sumMatrixValues(C, finalLsaRowInd, finalLsaColInd);

    return { C, lsaRowInd: finalLsaRowInd, lsaColInd: finalLsaColInd, ls };
}

/**
 * Extract submatrix for matched indices
 */
function extractC(C: number[][], iSet: Set<number>, jSet: Set<number>, m: number, n: number): number[][] {
    const size = m + n;
    const rowInd: number[] = [];
    const colInd: number[] = [];

    for (let k = 0; k < size; k++) {
        if (iSet.has(k) || jSet.has(k - m)) {
            rowInd.push(k);
        }
        if (jSet.has(k) || iSet.has(k - n)) {
            colInd.push(k);
        }
    }

    const result: number[][] = [];
    for (const r of rowInd) {
        const row: number[] = [];
        for (const c of colInd) {
            row.push(C[r][c]);
        }
        result.push(row);
    }

    return result;
}

/**
 * Reduce cost matrix by removing matched indices
 */
function reduceC(C: number[][], iSet: Set<number>, jSet: Set<number>, m: number, n: number): number[][] {
    const size = m + n;
    const rowInd: number[] = [];
    const colInd: number[] = [];

    for (let k = 0; k < size; k++) {
        if (!iSet.has(k) && !jSet.has(k - m)) {
            rowInd.push(k);
        }
        if (!jSet.has(k) && !iSet.has(k - n)) {
            colInd.push(k);
        }
    }

    const result: number[][] = [];
    for (const r of rowInd) {
        const row: number[] = [];
        for (const c of colInd) {
            row.push(C[r][c]);
        }
        result.push(row);
    }

    return result;
}

/**
 * Reduce index array
 */
function reduceInd(ind: number[], iSet: Set<number>): number[] {
    const result: number[] = [];
    for (const k of ind) {
        if (!iSet.has(k)) {
            let newK = k;
            for (const i of iSet) {
                if (i < k) {
                    newK--;
                }
            }
            result.push(newK);
        }
    }
    return result;
}

/**
 * Main optimize_edit_paths generator function
 */
export function* optimizeEditPaths(
    G1: MultiGraph,
    G2: MultiGraph,
    options: GEDOptions = {}
): Generator<[NodeEditPath, EdgeEditPath, number]> {
    const { nodeSubstCost, nodeDelCost, nodeInsCost, edgeSubstCost, edgeDelCost, edgeInsCost, upperBound } = options;

    // Initialize pending nodes
    const pendingU = [...G1.nodes];
    const pendingV = [...G2.nodes];

    const initialCost = 0;

    // Build vertex cost matrix
    const mV = pendingU.length;
    const nV = pendingV.length;
    const sizeV = mV + nV;

    const CV = createMatrix(sizeV, sizeV, 0);

    // Fill substitution costs
    if (nodeSubstCost) {
        for (let i = 0; i < mV; i++) {
            for (let j = 0; j < nV; j++) {
                CV[i][j] = nodeSubstCost(G1.getNodeData(pendingU[i]), G2.getNodeData(pendingV[j]));
            }
        }
    }

    // Deletion costs
    const delCostsV: number[] = [];
    if (nodeDelCost) {
        for (const u of pendingU) {
            delCostsV.push(nodeDelCost(G1.getNodeData(u)));
        }
    } else {
        for (let i = 0; i < mV; i++) {
            delCostsV.push(1);
        }
    }

    // Insertion costs
    const insCostsV: number[] = [];
    if (nodeInsCost) {
        for (const v of pendingV) {
            insCostsV.push(nodeInsCost(G2.getNodeData(v)));
        }
    } else {
        for (let i = 0; i < nV; i++) {
            insCostsV.push(1);
        }
    }

    // Calculate inf value
    let infV = 1;
    for (let i = 0; i < mV; i++) {
        for (let j = 0; j < nV; j++) {
            infV += CV[i][j];
        }
    }
    for (const c of delCostsV) infV += c;
    for (const c of insCostsV) infV += c;

    // Fill deletion part (diagonal in upper right)
    for (let i = 0; i < mV; i++) {
        for (let j = 0; j < mV; j++) {
            CV[i][nV + j] = i === j ? delCostsV[i] : infV;
        }
    }

    // Fill insertion part (diagonal in lower left)
    for (let i = 0; i < nV; i++) {
        for (let j = 0; j < nV; j++) {
            CV[mV + i][j] = i === j ? insCostsV[i] : infV;
        }
    }

    const Cv = makeCostMatrix(CV, mV, nV);

    // Build edge cost matrix
    const pendingG = [...G1.edges];
    const pendingH = [...G2.edges];

    const mE = pendingG.length;
    const nE = pendingH.length;
    const sizeE = mE + nE;

    const CE = createMatrix(sizeE, sizeE, 0);

    // Fill substitution costs
    if (edgeSubstCost) {
        for (let i = 0; i < mE; i++) {
            const g = pendingG[i];
            for (let j = 0; j < nE; j++) {
                const h = pendingH[j];
                CE[i][j] = edgeSubstCost(G1.getEdgeData(g[0], g[1], g[2]), G2.getEdgeData(h[0], h[1], h[2]));
            }
        }
    }

    // Deletion costs
    const delCostsE: number[] = [];
    if (edgeDelCost) {
        for (const g of pendingG) {
            delCostsE.push(edgeDelCost(G1.getEdgeData(g[0], g[1], g[2])));
        }
    } else {
        for (let i = 0; i < mE; i++) {
            delCostsE.push(1);
        }
    }

    // Insertion costs
    const insCostsE: number[] = [];
    if (edgeInsCost) {
        for (const h of pendingH) {
            insCostsE.push(edgeInsCost(G2.getEdgeData(h[0], h[1], h[2])));
        }
    } else {
        for (let i = 0; i < nE; i++) {
            insCostsE.push(1);
        }
    }

    // Calculate inf value
    let infE = 1;
    for (let i = 0; i < mE; i++) {
        for (let j = 0; j < nE; j++) {
            infE += CE[i][j];
        }
    }
    for (const c of delCostsE) infE += c;
    for (const c of insCostsE) infE += c;

    // Fill deletion part
    for (let i = 0; i < mE; i++) {
        for (let j = 0; j < mE; j++) {
            CE[i][nE + j] = i === j ? delCostsE[i] : infE;
        }
    }

    // Fill insertion part
    for (let i = 0; i < nE; i++) {
        for (let j = 0; j < nE; j++) {
            CE[mE + i][j] = i === j ? insCostsE[i] : infE;
        }
    }

    const Ce = makeCostMatrix(CE, mE, nE);

    // Maximum cost for pruning
    let maxcostValue = 0;
    for (const row of Cv.C) {
        for (const val of row) {
            maxcostValue += val;
        }
    }
    for (const row of Ce.C) {
        for (const val of row) {
            maxcostValue += val;
        }
    }
    maxcostValue += 1;

    /**
     * Pruning function
     */
    function prune(cost: number): boolean {
        if (upperBound !== undefined && cost > upperBound) {
            return true;
        }
        if (cost > maxcostValue) {
            return true;
        }
        if (cost >= maxcostValue) {
            return true;
        }
        return false;
    }

    /**
     * Match edges helper function
     */
    function matchEdges(
        u: NodeId | null,
        v: NodeId | null,
        pendingGList: EdgeTuple[],
        pendingHList: EdgeTuple[],
        CeMatrix: CostMatrix,
        matchedUv: Array<[NodeId | null, NodeId | null]>
    ): [Array<[number, number]>, CostMatrix] {
        const M = pendingGList.length;
        const N = pendingHList.length;

        const substitutionPossible = M > 0 && N > 0;
        const atLeastOneNodeMatch = matchedUv.length === 0;

        let gInd: number[];
        let hInd: number[];

        if (atLeastOneNodeMatch && substitutionPossible) {
            gInd = [];
            hInd = [];
        } else {
            // Find edges incident to matched vertices
            // g_ind: edges (u,u) or edges (p,u), (u,p), (p,p) for any matched (p,q)
            gInd = [];
            for (let i = 0; i < M; i++) {
                const g = pendingGList[i];
                const gs = g[0];
                const gt = g[1];

                // Self-loop on current node
                if (gs === u && gt === u) {
                    gInd.push(i);
                    continue;
                }

                // Edge incident to previously matched nodes
                let found = false;
                for (const [p, _q] of matchedUv) {
                    if (p === null) {
                        continue;
                    }
                    if ((gs === p && gt === u) || (gs === u && gt === p) || (gs === p && gt === p)) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    gInd.push(i);
                }
            }

            hInd = [];
            for (let j = 0; j < N; j++) {
                const h = pendingHList[j];
                const hs = h[0];
                const ht = h[1];

                // Self-loop on current node
                if (hs === v && ht === v) {
                    hInd.push(j);
                    continue;
                }

                // Edge incident to previously matched nodes
                let found = false;
                for (const [_p, q] of matchedUv) {
                    if (q === null) {
                        continue;
                    }
                    if ((hs === q && ht === v) || (hs === v && ht === q) || (hs === q && ht === q)) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    hInd.push(j);
                }
            }
        }

        const m = gInd.length;
        const n = hInd.length;

        if (m > 0 || n > 0) {
            const gIndSet = new Set(gInd);
            const hIndSet = new Set(hInd);
            const C = extractC(CeMatrix.C, gIndSet, hIndSet, M, N);

            // Forbid structurally invalid matches
            // A match g<->h is valid if:
            // 1. g connects (p,u) or (u,p) and h connects (q,v) or (v,q) for some matched (p,q)
            // 2. OR g is a self-loop (u,u) or (p,p) for some matched (p,q)
            // 3. OR h is a self-loop (v,v) or (q,q) for some matched (p,q)
            for (let k = 0; k < m; k++) {
                const i = gInd[k];
                const g = pendingGList[i];
                const gs = g[0];
                const gt = g[1];

                for (let l = 0; l < n; l++) {
                    const j = hInd[l];
                    const h = pendingHList[j];
                    const hs = h[0];
                    const ht = h[1];

                    // Check condition 1: structural match between edge endpoints
                    let cond1 = false;
                    for (const [p, q] of matchedUv) {
                        if (p === null || q === null) {
                            continue;
                        }
                        // g in ((p, u), (u, p)) and h in ((q, v), (v, q))
                        const gMatches = (gs === p && gt === u) || (gs === u && gt === p);
                        const hMatches = (hs === q && ht === v) || (hs === v && ht === q);
                        if (gMatches && hMatches) {
                            cond1 = true;
                            break;
                        }
                    }

                    // Check condition 2: g is self-loop on u or any matched p
                    let cond2 = false;
                    if (gs === u && gt === u) {
                        cond2 = true;
                    } else {
                        for (const [p, _q] of matchedUv) {
                            if (p !== null && gs === p && gt === p) {
                                cond2 = true;
                                break;
                            }
                        }
                    }

                    // Check condition 3: h is self-loop on v or any matched q
                    let cond3 = false;
                    if (hs === v && ht === v) {
                        cond3 = true;
                    } else {
                        for (const [_p, q] of matchedUv) {
                            if (q !== null && hs === q && ht === q) {
                                cond3 = true;
                                break;
                            }
                        }
                    }

                    // If none of the conditions are met, forbid the match
                    if (!cond1 && !cond2 && !cond3) {
                        C[k][l] = infE;
                    }
                }
            }

            const localCe = makeCostMatrix(C, m, n);

            const ij: Array<[number, number]> = [];
            for (let idx = 0; idx < localCe.lsaRowInd.length; idx++) {
                const k = localCe.lsaRowInd[idx];
                const l = localCe.lsaColInd[idx];

                if (k < m || l < n) {
                    const newI = k < m ? gInd[k] : M + hInd[l];
                    const newJ = l < n ? hInd[l] : N + gInd[k];
                    ij.push([newI, newJ]);
                }
            }

            return [ij, localCe];
        }

        return [[], { C: [], lsaRowInd: [], lsaColInd: [], ls: 0 }];
    }

    /**
     * Reduce Ce after matching
     */
    function reduceCe(CeMatrix: CostMatrix, ij: Array<[number, number]>, m: number, n: number): CostMatrix {
        if (ij.length > 0) {
            const iSet = new Set(ij.map(([i, _]) => i));
            const jSet = new Set(ij.map(([_, j]) => j));

            const mI = m - [...iSet].filter((t) => t < m).length;
            const nJ = n - [...jSet].filter((t) => t < n).length;

            return makeCostMatrix(reduceC(CeMatrix.C, iSet, jSet, m, n), mI, nJ);
        }
        return CeMatrix;
    }

    /**
     * Get edit operations generator
     */
    function* getEditOps(
        matchedUv: Array<[NodeId | null, NodeId | null]>,
        pendingUList: NodeId[],
        pendingVList: NodeId[],
        CvMatrix: CostMatrix,
        pendingGList: EdgeTuple[],
        pendingHList: EdgeTuple[],
        CeMatrix: CostMatrix,
        matchedCost: number
    ): Generator<[[number, number], CostMatrix, Array<[number, number]>, CostMatrix, number]> {
        const m = pendingUList.length;
        const n = pendingVList.length;

        // Find the minimum cost assignment from LSA
        let minI = -1;
        let minJ = -1;

        for (let idx = 0; idx < CvMatrix.lsaRowInd.length; idx++) {
            const k = CvMatrix.lsaRowInd[idx];
            const l = CvMatrix.lsaColInd[idx];

            if (k < m || l < n) {
                if (minI === -1 || k < minI || (k === minI && l < minJ)) {
                    minI = k;
                    minJ = l;
                }
            }
        }

        // Find actual minimum
        let bestIdx = -1;
        for (let idx = 0; idx < CvMatrix.lsaRowInd.length; idx++) {
            const k = CvMatrix.lsaRowInd[idx];
            const l = CvMatrix.lsaColInd[idx];
            if (k < m || l < n) {
                if (bestIdx === -1) {
                    bestIdx = idx;
                } else {
                    const bestK = CvMatrix.lsaRowInd[bestIdx];
                    const bestL = CvMatrix.lsaColInd[bestIdx];
                    if (k < bestK || (k === bestK && l < bestL)) {
                        bestIdx = idx;
                    }
                }
            }
        }

        if (bestIdx === -1) {
            return;
        }

        const i = CvMatrix.lsaRowInd[bestIdx];
        const j = CvMatrix.lsaColInd[bestIdx];

        const [xy, localCe] = matchEdges(
            i < m ? pendingUList[i] : null,
            j < n ? pendingVList[j] : null,
            pendingGList,
            pendingHList,
            CeMatrix,
            matchedUv
        );

        const CeXy = reduceCe(CeMatrix, xy, pendingGList.length, pendingHList.length);

        if (!prune(matchedCost + CvMatrix.ls + localCe.ls + CeXy.ls)) {
            // Get reduced Cv efficiently
            const iSet = new Set([i, m + j]);
            const jSet = new Set([j, n + i]);

            const newC = reduceC(CvMatrix.C, new Set([i]), new Set([j]), m, n);
            const newLsaRowInd = reduceInd(CvMatrix.lsaRowInd, iSet);
            const newLsaColInd = reduceInd(CvMatrix.lsaColInd, jSet);

            const CvIj: CostMatrix = {
                C: newC,
                lsaRowInd: newLsaRowInd,
                lsaColInd: newLsaColInd,
                ls: CvMatrix.ls - CvMatrix.C[i][j]
            };

            yield [[i, j], CvIj, xy, CeXy, CvMatrix.C[i][j] + localCe.ls];
        }

        // Other candidates
        const other: Array<[[number, number], CostMatrix, Array<[number, number]>, CostMatrix, number]> = [];

        const fixedI = i;
        const fixedJ = j;

        const candidates: Array<[number, number]> = [];

        if (m <= n) {
            for (let t = 0; t < m + n; t++) {
                if (t !== fixedI && (t < m || t === m + fixedJ)) {
                    candidates.push([t, fixedJ]);
                }
            }
        } else {
            for (let t = 0; t < m + n; t++) {
                if (t !== fixedJ && (t < n || t === n + fixedI)) {
                    candidates.push([fixedI, t]);
                }
            }
        }

        for (const [candI, candJ] of candidates) {
            if (prune(matchedCost + CvMatrix.C[candI][candJ] + CeMatrix.ls)) {
                continue;
            }

            const iSetCand = new Set([candI]);
            const jSetCand = new Set([candJ]);

            const CvIjCand = makeCostMatrix(
                reduceC(CvMatrix.C, iSetCand, jSetCand, m, n),
                candI < m ? m - 1 : m,
                candJ < n ? n - 1 : n
            );

            if (prune(matchedCost + CvMatrix.C[candI][candJ] + CvIjCand.ls + CeMatrix.ls)) {
                continue;
            }

            const [xyCand, localCeCand] = matchEdges(
                candI < m ? pendingUList[candI] : null,
                candJ < n ? pendingVList[candJ] : null,
                pendingGList,
                pendingHList,
                CeMatrix,
                matchedUv
            );

            if (prune(matchedCost + CvMatrix.C[candI][candJ] + CvIjCand.ls + localCeCand.ls)) {
                continue;
            }

            const CeXyCand = reduceCe(CeMatrix, xyCand, pendingGList.length, pendingHList.length);

            if (prune(matchedCost + CvMatrix.C[candI][candJ] + CvIjCand.ls + localCeCand.ls + CeXyCand.ls)) {
                continue;
            }

            other.push([[candI, candJ], CvIjCand, xyCand, CeXyCand, CvMatrix.C[candI][candJ] + localCeCand.ls]);
        }

        // Sort by lower-bound cost estimate
        other.sort((a, b) => a[4] + a[1].ls + a[3].ls - (b[4] + b[1].ls + b[3].ls));

        yield* other;
    }

    /**
     * Get edit paths generator - the main recursive function
     */
    function* getEditPaths(
        matchedUv: Array<[NodeId | null, NodeId | null]>,
        pendingUList: NodeId[],
        pendingVList: NodeId[],
        CvMatrix: CostMatrix,
        matchedGh: Array<[EdgeTuple | null, EdgeTuple | null]>,
        pendingGList: EdgeTuple[],
        pendingHList: EdgeTuple[],
        CeMatrix: CostMatrix,
        matchedCost: number
    ): Generator<[NodeEditPath, EdgeEditPath, number]> {
        if (prune(matchedCost + CvMatrix.ls + CeMatrix.ls)) {
            return;
        }

        if (Math.max(pendingUList.length, pendingVList.length) === 0) {
            // Path completed!
            maxcostValue = Math.min(maxcostValue, matchedCost);
            yield [[...matchedUv], [...matchedGh], matchedCost];
            return;
        }

        const editOps = getEditOps(
            matchedUv,
            pendingUList,
            pendingVList,
            CvMatrix,
            pendingGList,
            pendingHList,
            CeMatrix,
            matchedCost
        );

        for (const [ij, CvIj, xy, CeXy, editCost] of editOps) {
            const [i, j] = ij;

            if (prune(matchedCost + editCost + CvIj.ls + CeXy.ls)) {
                continue;
            }

            // Dive deeper
            const u = i < pendingUList.length ? pendingUList.splice(i, 1)[0] : null;
            const v = j < pendingVList.length ? pendingVList.splice(j, 1)[0] : null;
            matchedUv.push([u, v]);

            const lenG = pendingGList.length;
            const lenH = pendingHList.length;

            for (const [x, y] of xy) {
                matchedGh.push([x < lenG ? pendingGList[x] : null, y < lenH ? pendingHList[y] : null]);
            }

            const sortedX = [...new Set(xy.map(([x, _]) => x))].sort((a, b) => a - b);
            const sortedY = [...new Set(xy.map(([_, y]) => y))].sort((a, b) => a - b);

            const G: Array<EdgeTuple | null> = [];
            const H: Array<EdgeTuple | null> = [];

            for (let idx = sortedX.length - 1; idx >= 0; idx--) {
                const x = sortedX[idx];
                if (x < pendingGList.length) {
                    G.unshift(pendingGList.splice(x, 1)[0]);
                } else {
                    G.unshift(null);
                }
            }

            for (let idx = sortedY.length - 1; idx >= 0; idx--) {
                const y = sortedY[idx];
                if (y < pendingHList.length) {
                    H.unshift(pendingHList.splice(y, 1)[0]);
                } else {
                    H.unshift(null);
                }
            }

            yield* getEditPaths(
                matchedUv,
                pendingUList,
                pendingVList,
                CvIj,
                matchedGh,
                pendingGList,
                pendingHList,
                CeXy,
                matchedCost + editCost
            );

            // Backtrack
            if (u !== null) {
                pendingUList.splice(i, 0, u);
            }
            if (v !== null) {
                pendingVList.splice(j, 0, v);
            }
            matchedUv.pop();

            for (let idx = 0; idx < sortedX.length; idx++) {
                const x = sortedX[idx];
                const g = G[idx];
                if (g !== null) {
                    pendingGList.splice(x, 0, g);
                }
            }

            for (let idx = 0; idx < sortedY.length; idx++) {
                const y = sortedY[idx];
                const h = H[idx];
                if (h !== null) {
                    pendingHList.splice(y, 0, h);
                }
            }

            for (let idx = 0; idx < xy.length; idx++) {
                matchedGh.pop();
            }
        }
    }

    // Now go!
    const doneUv: Array<[NodeId | null, NodeId | null]> = [];

    yield* getEditPaths(doneUv, pendingU, pendingV, Cv, [], pendingG, pendingH, Ce, initialCost);
}

/**
 * Generator for consecutive approximations of GED
 */
export function* optimizeGraphEditDistance(
    G1: MultiGraph,
    G2: MultiGraph,
    options: Omit<GEDOptions, "strictlyDecreasing" | "roots" | "timeout"> = {}
): Generator<number> {
    for (const [_nodePath, _edgePath, cost] of optimizeEditPaths(G1, G2, options)) {
        yield cost;
    }
}

/**
 * Create an n x n matrix filled with a value
 */
function createMatrix(n: number, m: number, fill: number = 0): number[][] {
    return Array.from({ length: n }, () => new Array(m).fill(fill));
}

/**
 * Sum values at specific indices
 */
function sumMatrixValues(matrix: number[][], rowInd: number[], colInd: number[]): number {
    let sum = 0;
    for (let i = 0; i < rowInd.length; i++) {
        sum += matrix[rowInd[i]][colInd[i]];
    }
    return sum;
}
