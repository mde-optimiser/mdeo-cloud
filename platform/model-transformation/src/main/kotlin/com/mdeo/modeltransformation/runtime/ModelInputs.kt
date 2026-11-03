package com.mdeo.modeltransformation.runtime

import com.mdeo.modeltransformation.graph.ModelGraph

/**
 * The runtime value of a model: of a `model` input, of an element of a `List<model>` input (a
 * [com.mdeo.modeltransformation.compiler.CollectionValue] of these), or of a loop variable over
 * one.
 *
 * A model input does not live in a graph of its own: for one run, its vertices are loaded into
 * the graph of the implicit model, tagged with [slot] ([ModelGraph.loadModelInput]), and a model
 * value only names that slot. Passing it on — as a call argument, or by iterating a list of them —
 * passes the slot by reference, so a model is loaded once per run however deep the call chain
 * that reads it.
 *
 * @param slot The model-input slot, at least 1.
 */
data class ModelSlot(val slot: Int) {
    init {
        require(slot > ModelGraph.IMPLICIT_MODEL_SLOT) { "A model-input slot is at least 1, not $slot" }
    }

    override fun toString(): String = "model $slot"
}

/**
 * The models passed to a model input of a top-level run
 * ([TransformationEngine.execute]): read-only models of the transformation's metamodel, given as
 * [ModelGraph]s of either backend. They are copied into the run's graph and never changed.
 */
sealed interface ModelArgument {

    /** The models, in order. */
    val models: List<ModelGraph>

    /**
     * The model of an `in x: model` input.
     *
     * @param model The model.
     */
    data class Single(val model: ModelGraph) : ModelArgument {
        override val models: List<ModelGraph> get() = listOf(model)
    }

    /**
     * The models of an `in xs: List<model>` input, in the order a `for` loop over it visits them.
     *
     * @param models The models; may be empty.
     */
    data class Many(override val models: List<ModelGraph>) : ModelArgument

    companion object {
        /** The argument of an `in x: model` input. */
        fun of(model: ModelGraph): ModelArgument = Single(model)

        /** The argument of an `in xs: List<model>` input. */
        fun of(models: List<ModelGraph>): ModelArgument = Many(models)
    }
}

/**
 * An enum literal passed as the value of an input of a top-level run
 * ([TransformationEngine.execute]), e.g. `Kind.SERVICE` of a configuration.
 *
 * @param enumName The enum's name.
 * @param entry The literal's name.
 */
data class EnumLiteral(val enumName: String, val entry: String) {

    /** The value the engine holds an enum literal as (the value of `Kind.SERVICE` in a transformation). */
    val runtimeValue: String get() = "`$enumName`.`$entry`"

    override fun toString(): String = "$enumName.$entry"
}
