package com.mdeo.optimizer.rulegen

import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.optimizer.config.RefinementConfig

/**
 * The operators generated for an optimisation.
 *
 * @param mutations The operators, in the order their specs were given.
 * @param warnings Notes on operators that could not be generated or are limited, for the user.
 */
data class GenerationResult(
    val mutations: List<GeneratedMutation>,
    val warnings: List<String>
) {
    /** The warnings as a Markdown page for the results, or `null` when there are none. */
    fun warningsReport(): String? =
        if (warnings.isEmpty()) null
        else "# Mutation generation warnings\n\n" + warnings.joinToString("\n") { "- $it" } + "\n"
}

/** Whether an optimisation can start with generated operators. */
sealed interface Preparation {
    /** The operators were generated and the initial model keeps every refined bound. */
    data class Ready(val result: GenerationResult) : Preparation

    /** The run cannot start, for [reason]. */
    data class Rejected(val reason: String) : Preparation
}

/**
 * Generates consistency-preserving mutation operators after Burdusel et al., "Automatic
 * generation of atomic multiplicity-preserving search operators for search-based model
 * engineering" (SoSyM 2021) and the MDEOptimiser implementation.
 *
 * Operators are generated against the metamodel with every refinement applied, so applied to a
 * model that respects the (refined) multiplicities they produce one that does too. Unlike the
 * papers, no operators are generated for the unrefined metamodel: those could break the
 * refinements.
 */
object MutationRuleGenerator {

    /**
     * Generates the operators [specs] ask for.
     *
     * @throws RuleGenerationException when a spec or a refinement names something the metamodel
     *   does not have, or a refinement is invalid.
     */
    fun generate(
        metamodelData: MetamodelData,
        specs: List<MutationRuleSpec>,
        refinements: List<RefinementConfig> = emptyList()
    ): GenerationResult {
        val info = MetamodelInfo(metamodelData, refinements.map { MultiplicityOverride(it.className, it.fieldName, it.lower, it.upper) })
        val generator = SpecsGenerator(info)
        val operators = LinkedHashMap<String, OperatorSpec>()
        for (spec in specs) {
            val generated = try {
                generator.generate(spec)
            } catch (e: RuleGenerationException) {
                throw RuleGenerationException("${describe(spec)}: ${e.message}")
            }
            for (operator in generated) {
                val previous = operators.putIfAbsent(operator.name, operator)
                check(previous == null || previous == operator) { "two different operators named '${operator.name}'" }
            }
        }
        val warnings = generator.warnings.distinct().toMutableList()
        val mutations = operators.values.map { GeneratedMutation(it.name, MutationAstBuilder.build(it, info, warnings)) }
        return GenerationResult(mutations, warnings.distinct())
    }

    /**
     * Generates the operators [specs] ask for and checks that [initialModel] keeps every refined
     * bound: the operators only keep bounds a model already has. A spec or refinement that cannot
     * be generated, or a model outside the refinements, rejects the run with the reason.
     */
    fun prepare(
        metamodelData: MetamodelData,
        specs: List<MutationRuleSpec>,
        refinements: List<RefinementConfig>,
        initialModel: ModelData
    ): Preparation = try {
        val generated = generate(metamodelData, specs, refinements)
        val info = MetamodelInfo(metamodelData, refinements.map { MultiplicityOverride(it.className, it.fieldName, it.lower, it.upper) })
        val violations = MultiplicityChecker.violations(initialModel, info, info.refinedEnds)
        if (violations.isEmpty()) Preparation.Ready(generated)
        else Preparation.Rejected("The initial model violates the goal's refinements: " + violations.joinToString("; "))
    } catch (e: RuleGenerationException) {
        Preparation.Rejected("Cannot generate mutation operators: ${e.message}")
    }

    private fun describe(spec: MutationRuleSpec): String {
        val keyword = if (spec.action == MutationAction.ALL) "mutate" else spec.action.name.lowercase()
        return "$keyword ${spec.node}${spec.edge?.let { ".$it" } ?: ""}"
    }
}
