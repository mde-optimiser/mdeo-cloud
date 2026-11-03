package com.mdeo.optimizer.rulegen

import com.mdeo.metamodel.data.ModelData

/**
 * Checks a model against the multiplicities of a [MetamodelInfo]: every object holds between
 * `lower` and `upper` links through each of its named ends, and is contained by at most one whole.
 */
object MultiplicityChecker {

    /**
     * Returns one message per violation, or an empty list when [model] is consistent.
     *
     * @param ends When given, only the bounds of these ends are checked.
     */
    fun violations(model: ModelData, info: MetamodelInfo, ends: Collection<AssociationEnd>? = null): List<String> {
        val classOf = model.instances.associate { it.name to it.className }
        val counts = HashMap<Pair<String, AssociationEnd>, Int>()
        val containers = HashMap<String, Int>()
        val result = mutableListOf<String>()

        for (link in model.links) {
            val sourceClass = classOf[link.sourceName]
            val targetClass = classOf[link.targetName]
            if (sourceClass == null || targetClass == null) {
                result += "link ${describe(link.sourceName, link.sourceProperty)} -- ${describe(link.targetName, link.targetProperty)} names an unknown object"
                continue
            }
            fun find(fromProperty: String?, fromClass: String, toProperty: String?, toClass: String) =
                info.allEnds.firstOrNull {
                    it.name == fromProperty && it.opposite.name == toProperty &&
                        it.owner in info.supertypesOf(fromClass) && it.target in info.supertypesOf(toClass)
                }
            val sourceEnd = find(link.sourceProperty, sourceClass, link.targetProperty, targetClass)
                ?: find(link.targetProperty, targetClass, link.sourceProperty, sourceClass)?.opposite
            if (sourceEnd == null) {
                result += "link ${describe(link.sourceName, link.sourceProperty)} -- ${describe(link.targetName, link.targetProperty)} matches no association"
                continue
            }
            counts.merge(link.sourceName to sourceEnd, 1, Int::plus)
            counts.merge(link.targetName to sourceEnd.opposite, 1, Int::plus)
            if (sourceEnd.isContainer) containers.merge(link.sourceName, 1, Int::plus)
            if (sourceEnd.opposite.isContainer) containers.merge(link.targetName, 1, Int::plus)
        }

        for (instance in model.instances) {
            for (end in info.endsOf(instance.className)) {
                if (ends != null && end !in ends) continue
                val count = counts[instance.name to end] ?: 0
                if (count < end.lower || (end.isBounded && count > end.upper)) {
                    result += "${instance.name}.${end.name} has $count link(s), " +
                        "outside ${MetamodelInfo.bounds(end.lower, end.upper)}"
                }
            }
            if (ends == null && (containers[instance.name] ?: 0) > 1) {
                result += "${instance.name} is contained by ${containers[instance.name]} wholes"
            }
        }
        return result
    }

    private fun describe(obj: String, property: String?) = if (property == null) obj else "$obj.$property"
}
