package com.mdeo.optimizer.rulegen

/**
 * Decides which operators a [MutationRuleSpec] stands for.
 *
 * The decisions follow the case tables of Burdusel et al. (thesis Tables 5.1–5.4, as implemented
 * by MDEOptimiser), not the thesis pseudocode, whose change and swap branches are unreachable.
 * For the selected end `A.e[n..m]` with opposite `[k..l]` (an unnamed opposite is `[0..*]`):
 *
 * - **Edges:** `n = m` gives SWAP, plus CHANGE_TARGET unless `k = l` too; otherwise `k = l` gives
 *   CHANGE_SOURCE; otherwise ADD and/or REMOVE. CHANGE_TARGET is not in the papers: without it a
 *   fixed end could only ever exchange targets, never use a target nobody uses yet.
 * - **Create:** per required end, `l = *` gives CONNECT; `k = l` gives LB_REPAIR, plus
 *   LB_REPAIR_MULTI if `n > 1`, or nothing at all if `n = m`; otherwise CONNECT, plus LB_REPAIR and
 *   LB_REPAIR_MULTI as for `k = l` unless `n = m`. LB_REPAIR takes all `n` targets from one donor
 *   that keeps `n`, so it is left out when `m < 2n`. One operator per combination of the required
 *   ends' choices (the papers' iterative repair) and per container.
 * - **Delete:** one operator; neighbours whose end has `k = l > 0` are re-homed, neighbours whose
 *   end has `k > 0` otherwise must keep `k` partners. None when every object holds an end that is
 *   fixed at both sides.
 */
class SpecsGenerator(private val info: MetamodelInfo) {

    /** Notes on operators that could not be generated, for the user. */
    val warnings = mutableListOf<String>()

    /**
     * Returns the operators [spec] stands for.
     *
     * @throws RuleGenerationException when the spec names an unknown class or end, or an attribute.
     */
    fun generate(spec: MutationRuleSpec): List<OperatorSpec> {
        if (!info.hasClass(spec.node)) throw RuleGenerationException("unknown class '${spec.node}'")
        val edge = spec.edge
        if (edge == null) {
            return when (spec.action) {
                MutationAction.ALL -> create(spec.node) + delete(spec.node) +
                    info.endsOf(spec.node).flatMap { edges(spec.node, it, add = true, remove = true) }
                MutationAction.CREATE -> create(spec.node)
                MutationAction.DELETE -> delete(spec.node)
                MutationAction.ADD -> info.endsOf(spec.node).flatMap { edges(spec.node, it, add = true, remove = false) }
                MutationAction.REMOVE -> info.endsOf(spec.node).flatMap { edges(spec.node, it, add = false, remove = true) }
            }
        }
        val end = info.endOf(spec.node, edge) ?: throw unknownEnd(spec.node, edge)
        return when (spec.action) {
            MutationAction.ALL -> edges(spec.node, end, add = true, remove = true)
            MutationAction.ADD -> edges(spec.node, end, add = true, remove = false)
            MutationAction.REMOVE -> edges(spec.node, end, add = false, remove = true)
            MutationAction.CREATE, MutationAction.DELETE ->
                throw RuleGenerationException("${spec.action} applies to a class, not to the end '${spec.node}.$edge'")
        }
    }

    private fun unknownEnd(className: String, name: String): RuleGenerationException =
        if (info.propertiesOf(className).any { it.name == name }) {
            RuleGenerationException("'$className.$name' is an attribute; operators can only be generated for association ends")
        } else {
            RuleGenerationException("class '$className' has no association end '$name'")
        }

    // -------------------------------------------------------------------------
    // Edges
    // -------------------------------------------------------------------------

    private fun edges(className: String, end: AssociationEnd, add: Boolean, remove: Boolean): List<OperatorSpec> {
        if (end.upper == 0) return emptyList()
        val opposite = end.opposite
        // When one end has an upper bound and the other a lower bound, a model can be tight:
        // every link that could be added would exceed an upper bound and every link that could
        // be removed would fall below a lower one, although other linkings of the same objects
        // are consistent. A swap keeps every object's number of links, so it is always possible
        // and connects them.
        val tight = (end.isBounded && opposite.lower > 0) || (opposite.isBounded && end.lower > 0)
        val swap = EdgeKind.SWAP.takeIf { add && remove && tight }
        val kinds = when {
            end.isFixed -> listOfNotNull(EdgeKind.SWAP, EdgeKind.CHANGE_TARGET.takeUnless { opposite.isFixed })
            opposite.isFixed -> listOfNotNull(EdgeKind.CHANGE_SOURCE, swap)
            else -> listOfNotNull(EdgeKind.ADD.takeIf { add }, EdgeKind.REMOVE.takeIf { remove }, swap)
        }
        if (kinds.any { it != EdgeKind.REMOVE }) warnIfRecursive(end)
        return kinds.map { EdgeSpec(className, end, it) }
    }

    /**
     * Notes that operators linking existing objects through [end] can nest an object inside one
     * of its own descendants when [end] belongs to a composition whose part may be its own whole:
     * a pattern can only look a fixed number of levels up, so containment cycles are not ruled out.
     */
    private fun warnIfRecursive(end: AssociationEnd) {
        if ((end.isContainment || end.isContainer) && info.compatible(end.owner, end.target)) {
            val containment = if (end.isContainment) end else end.opposite
            warnings += "Operators moving objects through the recursive composition '$containment' may place an " +
                "object inside one of its own descendants."
        }
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    private fun create(className: String): List<OperatorSpec> {
        val concrete = info.concreteSubtypesOf(className)
        if (concrete.isEmpty()) {
            warnings += "No create operator for '$className': neither it nor a subclass can be instantiated."
        }
        return concrete.flatMap { createConcrete(it) }
    }

    private fun createConcrete(className: String): List<OperatorSpec> {
        val required = info.endsOf(className).filter { it.lower > 0 && !it.isContainer }
        val options = required.map { end -> end to createOptions(end) }
        val impossible = options.firstOrNull { it.second.isEmpty() }
        if (impossible != null) {
            val end = impossible.first
            warnings += "No create operator for '$className': '$end' and its opposite '${end.opposite}' are both " +
                "fixed, so a new object can only be linked by changing other objects recursively."
            return emptyList()
        }
        required.filter { it.isContainment }.forEach { warnIfRecursive(it) }
        val combinations = options.fold(listOf(emptyList<Requirement>())) { acc, (end, repairs) ->
            acc.flatMap { prefix -> repairs.map { prefix + Requirement(end, it) } }
        }
        val requiredContainerEnds = info.endsOf(className).filter { it.isContainer && it.lower > 0 }
        val possible = info.containerContextsOf(className).filter { ctx -> requiredContainerEnds.all { it === ctx.containerEnd } }
        // A whole that holds a fixed number of parts can never take another one.
        val open = possible.filterNot { it.containmentEnd.isFixed }
        for (ctx in possible - open.toSet()) {
            warnings += "No '$className' is created inside a '${ctx.wholeClass}': '${ctx.containmentEnd}' holds a fixed number of parts."
        }
        val contexts: List<ContainerContext?> = when {
            open.isNotEmpty() -> open
            requiredContainerEnds.isEmpty() -> listOf(null)
            else -> {
                warnings += "No create operator for '$className': no composition it must be part of can take another part."
                return emptyList()
            }
        }
        return contexts.flatMap { ctx -> combinations.map { CreateSpec(className, ctx, it) } }
    }

    private fun createOptions(end: AssociationEnd): List<CreateRepair> {
        val n = end.lower
        val opposite = end.opposite
        val steal = buildList {
            if (!end.isFixed) {
                if (!end.isBounded || end.upper >= 2 * n) add(CreateRepair.LB_REPAIR)
                if (n > 1) add(CreateRepair.LB_REPAIR_MULTI)
            }
        }
        return when {
            !opposite.isBounded -> listOf(CreateRepair.CONNECT)
            opposite.isFixed -> steal
            else -> listOf(CreateRepair.CONNECT) + steal
        }
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    private fun delete(className: String): List<OperatorSpec> {
        val guarded = mutableListOf<AssociationEnd>()
        val rehomed = mutableListOf<AssociationEnd>()
        for (end in info.endsPointingAt(className)) {
            if (end.lower == 0) continue
            val nodeSide = end.opposite
            if (end.isFixed && nodeSide.isFixed && nodeSide.lower > 0 && nodeSide.owner in info.supertypesOf(className)) {
                warnings += "No delete operator for '$className': every object holds '$nodeSide', whose partners need " +
                    "exactly ${end.lower} and no other object can take one more."
                return emptyList()
            }
            if (end.isFixed && !nodeSide.isFixed) {
                rehomed += end
                warnIfRecursive(end)
            } else {
                guarded += end
            }
        }
        return listOf(DeleteSpec(className, guarded, rehomed))
    }
}
