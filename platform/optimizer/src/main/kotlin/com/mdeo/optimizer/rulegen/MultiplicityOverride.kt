package com.mdeo.optimizer.rulegen

/**
 * A multiplicity override that replaces the lower/upper bounds of a specific reference when
 * building a [MetamodelInfo] for the solution-space ("S_") rule generation pass.
 *
 * @param className  The metamodel class that owns the reference.
 * @param refName    The reference (field) name to override.
 * @param lower      New lower bound.
 * @param upper      New upper bound (-1 = unbounded).
 */
data class MultiplicityOverride(
    val className: String,
    val refName: String,
    val lower: Int,
    val upper: Int
)
