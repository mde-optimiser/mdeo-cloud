package com.mdeo.optimizer.rulegen

/**
 * Translates a [MutationRuleSpec] into one or more [RepairSpec] objects grouped by operation
 * category ("CREATE", "DELETE", "ADD", "REMOVE").
 *
 * The decision logic implements the CPO generation tables from the CPO_Agent_Instructions:
 *
 * ### Table 1 – Create Node aCPSOs
 * A base node-creation spec ([RepairSpecType.CREATE]) is always emitted.  When a reference on
 * the new node has a positive lower bound **and** its opposite end has a finite upper bound:
 * - [RepairSpecType.CREATE_LB_REPAIR] is emitted (single-donor, n ≥ 1).
 * - [RepairSpecType.CREATE_LB_REPAIR_MULTI] is additionally emitted when n > 1 (multi-donor).
 *
 * ### Table 2 – Delete Node aCPSOs
 * A [RepairSpecType.DELETE] spec is always emitted.  For every reference whose opposite end has
 * **fixed cardinality** (k == l > 0):
 * - [RepairSpecType.DELETE_REPAIR_SINGLE] is emitted (move one neighbour to a single other node).
 * - [RepairSpecType.DELETE_REPAIR_MULTI] is additionally emitted when k > 1 (move k neighbours
 *   simultaneously, each to a different replacement node).
 *
 * ### Table 3 – Add Edge aCPSOs
 * - `lower == upper` (fixed cardinality) → [RepairSpecType.SWAP]
 * - `opposite.lower == 1` (target requires exactly one back-link) → [RepairSpecType.CHANGE]
 * - Otherwise → [RepairSpecType.ADD]
 *
 * ### Table 4 – Remove Edge aCPSOs
 * Uses the same decision tree as Add (above).
 */
class SpecsGenerator {

    /**
     * Produces the map of category → repair specs for the given [spec] and [info].
     *
     * Returns an empty map when [MutationRuleSpec.node] is not a known class in [info].
     */
    fun getRepairsForRuleSpec(
        spec: MutationRuleSpec,
        info: MetamodelInfo
    ): Map<String, List<RepairSpec>> {

        if (!info.classNames().contains(spec.node)) return emptyMap()

        val refs = if (spec.edge != null) {
            info.referencesForNode(spec.node).filter { it.refName == spec.edge }
        } else {
            info.referencesForNode(spec.node)
        }

        val result = mutableMapOf<String, MutableList<RepairSpec>>()

        when (spec.action) {
            MutationAction.ALL -> {
                generateCreate(spec.node, refs, result)
                generateDelete(spec.node, info, result)
                generateAdd(spec.node, refs, result)
                generateRemove(spec.node, refs, result)
            }
            MutationAction.CREATE -> generateCreate(spec.node, refs, result)
            MutationAction.DELETE -> generateDelete(spec.node, info, result)
            MutationAction.ADD -> generateAdd(spec.node, refs, result)
            MutationAction.REMOVE -> generateRemove(spec.node, refs, result)
        }

        return result
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun generateCreate(
        className: String,
        refs: List<ReferenceInfo>,
        result: MutableMap<String, MutableList<RepairSpec>>
    ) {
        // Base node-creation rule
        result.getOrPut("CREATE") { mutableListOf() }
            .add(RepairSpec(className, null, RepairSpecType.CREATE))

        // LB-repair variants: needed when the source lower > 0, not fixed-cardinality,
        // and opposite has a finite upper bound (can't always freely steal)
        for (ref in refs) {
            if (ref.lower > 0 && ref.opposite != null && ref.opposite.upper != -1) {
                // Single-donor repair (n ≥ 1)
                result.getOrPut("CREATE") { mutableListOf() }
                    .add(RepairSpec(className, ref.refName, RepairSpecType.CREATE_LB_REPAIR))
                // Multi-donor repair (n > 1): steal one target from each of n different donors
                if (ref.lower > 1) {
                    result.getOrPut("CREATE") { mutableListOf() }
                        .add(RepairSpec(className, ref.refName, RepairSpecType.CREATE_LB_REPAIR_MULTI))
                }
            }
        }
    }

    private fun generateDelete(
        className: String,
        info: MetamodelInfo,
        result: MutableMap<String, MutableList<RepairSpec>>
    ) {
        // Always emit the plain delete (engine adds WHERE guards for soft lower-bounds)
        result.getOrPut("DELETE") { mutableListOf() }
            .add(RepairSpec(className, null, RepairSpecType.DELETE))

        // For every reference whose opposite has FIXED cardinality (k == l > 0):
        // emit repair rules that atomically delete the node and reconnect its neighbours.
        val refs = info.referencesForNode(className)
        for (ref in refs) {
            val targetRefs = info.referencesForNode(ref.targetClass)
            val backRef = targetRefs.find {
                it.targetClass == className && it.isReverse == !ref.isReverse
            }
            if (backRef != null && backRef.lower == backRef.upper && backRef.lower > 0) {
                // Single-neighbour repair (k = l = 1 or k = l > 1, one per application)
                result.getOrPut("DELETE") { mutableListOf() }
                    .add(RepairSpec(className, ref.refName, RepairSpecType.DELETE_REPAIR_SINGLE))
                // Multi-neighbour repair (k = l > 1, handles k neighbours simultaneously)
                if (backRef.lower > 1) {
                    result.getOrPut("DELETE") { mutableListOf() }
                        .add(RepairSpec(className, ref.refName, RepairSpecType.DELETE_REPAIR_MULTI))
                }
            }
        }
    }

    private fun generateAdd(
        className: String,
        refs: List<ReferenceInfo>,
        result: MutableMap<String, MutableList<RepairSpec>>
    ) {
        for (ref in refs) {
            val type = edgeRepairType(ref, isRemove = false)
            result.getOrPut("ADD") { mutableListOf() }
                .add(RepairSpec(className, ref.refName, type))
        }
    }

    private fun generateRemove(
        className: String,
        refs: List<ReferenceInfo>,
        result: MutableMap<String, MutableList<RepairSpec>>
    ) {
        for (ref in refs) {
            val type = edgeRepairType(ref, isRemove = true)
            result.getOrPut("REMOVE") { mutableListOf() }
                .add(RepairSpec(className, ref.refName, type))
        }
    }

    /**
     * Chooses the repair type for an edge operation (both ADD and REMOVE follow the same logic):
     * - Fixed cardinality (`lower == upper`) → [RepairSpecType.SWAP]
     * - Opposite requires exactly one back-link (`opposite.lower == 1`) → [RepairSpecType.CHANGE]
     * - Everything else → [RepairSpecType.ADD] for add, [RepairSpecType.REMOVE] for remove
     */
    private fun edgeRepairType(ref: ReferenceInfo, isRemove: Boolean): RepairSpecType = when {
        ref.lower == ref.upper -> RepairSpecType.SWAP
        ref.opposite?.lower == 1 -> RepairSpecType.CHANGE
        else -> if (isRemove) RepairSpecType.REMOVE else RepairSpecType.ADD
    }
}
