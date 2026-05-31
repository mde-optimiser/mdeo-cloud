package com.mdeo.optimizer.rulegen

/**
 * Generates human-readable names for mutation operator rules.
 *
 * Naming conventions:
 * - Node operations: `ACTION_ClassName`
 * - Edge operations: `ACTION_ClassName_refName`
 * - Node-create with containment: `CREATE_ClassName_in_ContainerClass_via_refName`
 * - LB-repair suffix: `_LBREPAIR` (single-donor) / `_LBREPAIR_MULTI` (multi-donor)
 * - Delete-repair suffix: `_REPAIR_SG` (single neighbour) / `_REPAIR_MN` (multi neighbour)
 */
object MutationRuleNameGenerator {

    /**
     * Name for a node-level operation: `ACTION_ClassName`.
     */
    fun forNode(action: String, className: String): String = "${action}_${className}"

    /**
     * Name for an edge-level operation: `ACTION_ClassName_refName`.
     */
    fun forEdge(action: String, className: String, refName: String): String =
        "${action}_${className}_${refName}"

    /**
     * Name for a containment-context CREATE rule: `CREATE_ClassName_in_ContainerClass_via_refName`.
     */
    fun forNodeCreate(className: String, containerClass: String, refName: String): String =
        "CREATE_${className}_in_${containerClass}_via_${refName}"

    /**
     * Derives the operator name from a [RepairSpec].
     *
     * - [RepairSpecType.CREATE_LB_REPAIR]       → `_LBREPAIR` suffix
     * - [RepairSpecType.CREATE_LB_REPAIR_MULTI]  → `_LBREPAIR_MULTI` suffix
     * - [RepairSpecType.DELETE_REPAIR_SINGLE]    → `_REPAIR_SG` suffix
     * - [RepairSpecType.DELETE_REPAIR_MULTI]     → `_REPAIR_MN` suffix
     * - All other types: no suffix; action derived directly from the type name.
     */
    fun fromRepairSpec(spec: RepairSpec): String {
        val action = when (spec.type) {
            RepairSpecType.CREATE,
            RepairSpecType.CREATE_LB_REPAIR,
            RepairSpecType.CREATE_LB_REPAIR_MULTI -> "CREATE"
            RepairSpecType.DELETE,
            RepairSpecType.DELETE_REPAIR_SINGLE,
            RepairSpecType.DELETE_REPAIR_MULTI -> "DELETE"
            RepairSpecType.ADD    -> "ADD"
            RepairSpecType.REMOVE -> "REMOVE"
            RepairSpecType.CHANGE -> "CHANGE"
            RepairSpecType.SWAP   -> "SWAP"
        }
        val baseName = if (spec.edgeName != null) {
            forEdge(action, spec.className, spec.edgeName)
        } else {
            forNode(action, spec.className)
        }
        val suffix = when (spec.type) {
            RepairSpecType.CREATE_LB_REPAIR       -> "_LBREPAIR"
            RepairSpecType.CREATE_LB_REPAIR_MULTI -> "_LBREPAIR_MULTI"
            RepairSpecType.DELETE_REPAIR_SINGLE   -> "_REPAIR_SG"
            RepairSpecType.DELETE_REPAIR_MULTI    -> "_REPAIR_MN"
            else -> ""
        }
        return baseName + suffix
    }
}
