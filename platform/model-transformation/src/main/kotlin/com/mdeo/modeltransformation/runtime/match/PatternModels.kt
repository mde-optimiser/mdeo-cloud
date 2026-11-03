package com.mdeo.modeltransformation.runtime.match

import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.compiler.VariableBinding
import com.mdeo.modeltransformation.graph.ModelGraph
import com.mdeo.modeltransformation.runtime.ModelSlot
import com.mdeo.modeltransformation.runtime.TransformationExecutionContext

/**
 * The model slot of every object of a pattern, while model inputs are loaded into the graph
 * ([ModelGraph.modelInputSlots]).
 *
 * An object declared or referenced in a scope block `in donor { … }` (its
 * [com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance.model]) lives in the slot
 * the model value `donor` holds ([ModelSlot]); every other object in the implicit model
 * ([ModelGraph.IMPLICIT_MODEL_SLOT]). [MatchTraversalBuilder] filters each scan of a pattern
 * object by its slot. A walk along a link needs no filter: links never cross models, so it stays
 * in the model it starts in. Nor does the scan of a bound object the pattern only reads in an
 * expression, which may live in any model.
 *
 * Built only while model inputs are loaded; otherwise a match has no [PatternModels] and emits no
 * slot step at all.
 *
 * @param slots The slot of each object of the pattern (its conditions included), by name.
 */
internal class PatternModels private constructor(private val slots: Map<String, Int>) {

    /**
     * The model slot of the pattern object [name], or null for a name that is no object of the
     * pattern (a bound object an expression reads), whose scan is not filtered.
     */
    fun slotOf(name: String): Int? = slots[name]

    companion object {
        /**
         * Resolves the models of the objects of [elements] in [context].
         *
         * Also checks what the language guarantees about objects of model inputs, failing loudly
         * on a typed AST that breaks it rather than changing a model input: they are only matched
         * (no `create`/`delete`, no property assignment, no link created or deleted at them), and
         * no link connects objects of different models.
         *
         * @param elements The categorised pattern.
         * @param context The context the model values of the scope blocks are bound in.
         * @param modelGraph The graph matched in.
         * @return The models, or null when no model input is loaded (the pattern then has no
         *   object of one).
         * @throws IllegalStateException When the pattern breaks one of the rules above, or names
         *   a model that is not loaded.
         */
        fun of(
            elements: PatternCategories,
            context: TransformationExecutionContext,
            modelGraph: ModelGraph
        ): PatternModels? {
            if (modelGraph.modelInputSlots.isEmpty()) {
                checkNoModelInputObjects(elements)
                return null
            }
            val instances = elements.matchableInstances + elements.createInstances + elements.deleteInstances +
                elements.conditions.flatMap { condition ->
                    condition.condition.elements.filterIsInstance<TypedPatternObjectInstanceElement>()
                }
            val modelOf = HashMap<String, String?>()
            for (instance in instances) {
                modelOf[instance.objectInstance.name] = instance.objectInstance.model
            }
            checkReadOnly(elements, modelOf)
            val slotByModel = HashMap<String, Int>()
            val slots = modelOf.mapValues { (_, model) ->
                if (model == null) ModelGraph.IMPLICIT_MODEL_SLOT
                else slotByModel.getOrPut(model) { resolveSlot(model, context, modelGraph) }
            }
            return PatternModels(slots)
        }

        /**
         * Fails when an object of [elements] lives in a model input although none is loaded.
         * Allocation-free, as it runs for every match of a run without model inputs.
         */
        private fun checkNoModelInputObjects(elements: PatternCategories) {
            fun check(instance: TypedPatternObjectInstance) {
                val model = instance.model ?: return
                throw IllegalStateException("'${instance.name}' lives in model '$model', but no model input is loaded")
            }
            for (instance in elements.matchableInstances) check(instance.objectInstance)
            for (instance in elements.createInstances) check(instance.objectInstance)
            for (instance in elements.deleteInstances) check(instance.objectInstance)
            for (condition in elements.conditions) {
                for (element in condition.condition.elements) {
                    if (element is TypedPatternObjectInstanceElement) check(element.objectInstance)
                }
            }
        }

        /** The slot of the model value [model] bound in [context]. */
        private fun resolveSlot(model: String, context: TransformationExecutionContext, modelGraph: ModelGraph): Int {
            val value = (context.variableScope.getVariable(model) as? VariableBinding.ValueBinding)?.value
            val slot = (value as? ModelSlot)?.slot
                ?: throw IllegalStateException("'$model' is not a model value (it is bound to $value)")
            check(slot in modelGraph.modelInputSlots) { "The model '$model' ($value) is not loaded" }
            return slot
        }

        /**
         * Fails when the pattern changes an object of a model input or links objects of different
         * models.
         */
        private fun checkReadOnly(elements: PatternCategories, modelOf: Map<String, String?>) {
            fun fail(message: String): Nothing = throw IllegalStateException("$message: model inputs are read-only")
            for (instance in elements.createInstances + elements.deleteInstances) {
                instance.objectInstance.model?.let { model ->
                    fail("'${instance.objectInstance.name}' of model '$model' is ${instance.objectInstance.modifier}d")
                }
            }
            for (instance in elements.matchableInstances) {
                val model = instance.objectInstance.model ?: continue
                instance.objectInstance.properties.firstOrNull { it.operator == "=" }?.let { property ->
                    fail("'${instance.objectInstance.name}.${property.propertyName}' of model '$model' is assigned")
                }
            }
            for (link in elements.createLinks + elements.deleteLinks) {
                endModels(link, modelOf).firstOrNull { it != null }?.let { model ->
                    fail("A link at an object of model '$model' is ${link.link.modifier}d")
                }
            }
            val allLinks = elements.matchableLinks + elements.createLinks + elements.deleteLinks +
                elements.conditions.flatMap { condition -> condition.condition.elements.filterIsInstance<TypedPatternLinkElement>() }
            for (link in allLinks) {
                val ends = endModels(link, modelOf)
                check(ends[0] == ends[1]) {
                    "The link ${link.link.source.objectName} -- ${link.link.target.objectName} connects objects of " +
                        "different models (${ends[0] ?: "the implicit model"}, ${ends[1] ?: "the implicit model"})"
                }
            }
        }

        /** The models of the two ends of [link]; an end that is no object of the pattern counts as implicit. */
        private fun endModels(link: TypedPatternLinkElement, modelOf: Map<String, String?>): List<String?> =
            listOf(modelOf[link.link.source.objectName], modelOf[link.link.target.objectName])
    }
}
