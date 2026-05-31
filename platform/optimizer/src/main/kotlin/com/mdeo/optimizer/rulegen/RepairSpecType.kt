package com.mdeo.optimizer.rulegen

/**
 * Enumerates the kinds of consistency-preserving repair specs that [SpecsGenerator] can produce.
 *
 * Each value maps to a distinct structural mutation pattern:
 * - [CREATE]               – Create a new node (no edge involved).
 * - [CREATE_LB_REPAIR]      – Create a new node and steal one target from a single donor node to
 *                             satisfy a positive lower-bound (single-donor variant, n ≥ 1).
 * - [CREATE_LB_REPAIR_MULTI]– Create a new node and steal one target from each of n distinct donor
 *                             nodes (multi-donor variant, n > 1).
 * - [DELETE]                – Delete an existing node (with WHERE guards where needed).
 * - [DELETE_REPAIR_SINGLE]  – Delete a node and simultaneously move ONE neighbour (whose opposite
 *                             has fixed cardinality k=l>0) to a single other node of the same class.
 * - [DELETE_REPAIR_MULTI]   – Delete a node and simultaneously move k neighbours (k=l>1) each to a
 *                             different replacement node.
 * - [ADD]                   – Add a new edge between two existing nodes.
 * - [REMOVE]                – Remove an existing edge between two nodes.
 * - [CHANGE]                – Change the target of an existing edge (delete + create).
 * - [SWAP]                  – Swap the targets of two parallel edges (preserves cardinality).
 */
enum class RepairSpecType {
    CREATE,
    CREATE_LB_REPAIR,
    CREATE_LB_REPAIR_MULTI,
    DELETE,
    DELETE_REPAIR_SINGLE,
    DELETE_REPAIR_MULTI,
    ADD,
    REMOVE,
    CHANGE,
    SWAP
}
