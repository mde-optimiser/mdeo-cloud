package com.mdeo.optimizer.rulegen

/**
 * What one generated mutation operator does, before it is turned into a transformation.
 *
 * Notation in the docs below follows Burdusel et al.: the selected end is `A.e[n..m]` pointing at
 * `B`, and its opposite end — how many `A`s each `B` may have — is `[k..l]`.
 */
sealed interface OperatorSpec {
    /** The operator's name, unique for what it does. */
    val name: String
}

/** How a created node satisfies one of its required ends. */
enum class CreateRepair(val label: String) {
    /** Link `n` existing targets that still have room for another partner. */
    CONNECT("CONNECT"),

    /** Take all `n` targets from a single donor that keeps at least `n` others. */
    LB_REPAIR("LBREPAIR"),

    /** Take one target from each of `n` donors, each keeping at least `n`. */
    LB_REPAIR_MULTI("LBREPAIR_MULTI")
}

/**
 * Satisfies a created node's required [end] with [repair].
 */
data class Requirement(val end: AssociationEnd, val repair: CreateRepair)

/**
 * Creates an object of [className] inside [context] (or uncontained when there is none) and
 * satisfies each required end as its [requirements] say.
 */
data class CreateSpec(
    val className: String,
    val context: ContainerContext?,
    val requirements: List<Requirement>
) : OperatorSpec {
    override val name: String = buildString {
        append("CREATE_").append(className)
        if (context != null) {
            append("_in_").append(context.wholeClass)
            append("_via_").append(context.containmentEnd.name ?: context.containerEnd.name)
        }
        for (r in requirements) append('_').append(r.end.name).append('_').append(r.repair.label)
    }
}

/**
 * Deletes an object of [className] or of one of its subtypes.
 *
 * @param guarded Ends of neighbours that must keep their lower bound: the object can only be
 *   deleted when every neighbour linked through one of them keeps enough partners.
 * @param rehomed Ends of neighbours with a fixed count: every neighbour linked through one of
 *   them is moved to another object, so its count does not change.
 */
data class DeleteSpec(
    val className: String,
    val guarded: List<AssociationEnd>,
    val rehomed: List<AssociationEnd>
) : OperatorSpec {
    override val name: String = "DELETE_$className"
}

/** The edge operations of Burdusel et al.'s add/remove edge tables, plus Change-A. */
enum class EdgeKind {
    /** Link a source to one more target. */
    ADD,

    /** Unlink a source from one of its targets. */
    REMOVE,

    /** A target moves from one source to another; for a fixed opposite end (`k = l`). */
    CHANGE_SOURCE,

    /** A source moves one of its links to another target; for a fixed end (`n = m`, `k < l`). */
    CHANGE_TARGET,

    /** Two sources exchange a target each; for a fixed end (`n = m`). */
    SWAP
}

/**
 * Changes the links of [end] on objects of [className].
 */
data class EdgeSpec(
    val className: String,
    val end: AssociationEnd,
    val kind: EdgeKind
) : OperatorSpec {
    override val name: String = when (kind) {
        EdgeKind.ADD -> "ADD"
        EdgeKind.REMOVE -> "REMOVE"
        EdgeKind.CHANGE_SOURCE, EdgeKind.CHANGE_TARGET -> "CHANGE"
        EdgeKind.SWAP -> "SWAP"
    } + "_${className}_${end.name}"
}
