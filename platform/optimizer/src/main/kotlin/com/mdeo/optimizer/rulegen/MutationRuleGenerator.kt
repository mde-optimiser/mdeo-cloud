package com.mdeo.optimizer.rulegen

import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.optimizer.config.RefinementConfig
import org.slf4j.LoggerFactory

/**
 * Entry-point for automatic consistency-preserving mutation operator generation.
 *
 * Given a metamodel and a list of [MutationRuleSpec] entries this object produces a flat list of
 * [GeneratedMutation] objects – each containing a human-readable name and a compiled [TypedAst]
 * ready for execution by the model-transformation engine.
 *
 * ### Generation passes
 *
 * **Pass 1 – Base rules (prefix: none)**
 * Generated from the metamodel as-is.  The specs drive which classes and edges are considered.
 *
 * **Pass 2 – Solution-space rules (prefix: `S_`)**
 * Only performed when [refinements] is non-empty.  A second [MetamodelInfo] is constructed with
 * the refined multiplicities applied, and the same spec list is processed again.  Resulting rule
 * names receive the `"S_"` prefix so they can be distinguished from base rules.
 *
 * ### Deduplication
 * Rules with the same name are silently dropped so that duplicate [MutationRuleSpec] entries in
 * the input do not produce duplicate operators.
 *
 * ### Unknown classes
 * Any spec referencing a class that is not present in the metamodel is skipped with a warning log.
 */
object MutationRuleGenerator {

    private val log = LoggerFactory.getLogger(MutationRuleGenerator::class.java)

    private val specsGenerator = SpecsGenerator()

    /**
     * Generates mutation operators from [metamodelData] according to [specs].
     *
     * @param metamodelData  The metamodel to analyse.
     * @param specs          Operator generation specifications.
     * @param refinements    Optional multiplicity overrides for the solution-space pass.
     * @return               Deduplicated list of [GeneratedMutation] objects.
     */
    fun generate(
        metamodelData: MetamodelData,
        specs: List<MutationRuleSpec>,
        refinements: List<RefinementConfig> = emptyList()
    ): List<GeneratedMutation> {
        if (specs.isEmpty()) return emptyList()

        val baseInfo = MetamodelInfo(metamodelData)
        val result = mutableMapOf<String, GeneratedMutation>()

        // Pass 1: base rules
        generatePass(specs, baseInfo, metamodelData.path, prefix = "", result)

        // Pass 2: solution-space rules (only when refinements are declared)
        if (refinements.isNotEmpty()) {
            val overrides = refinements.map {
                MultiplicityOverride(
                    className = it.className,
                    refName = it.fieldName,
                    lower = it.lower,
                    upper = it.upper
                )
            }
            val refinedInfo = MetamodelInfo.withOverrides(metamodelData, overrides)
            generatePass(specs, refinedInfo, metamodelData.path, prefix = "S_", result)
        }

        return result.values.toList()
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Runs a single generation pass and merges results into [accumulator].
     * Already-seen names are skipped (deduplication across passes).
     */
    private fun generatePass(
        specs: List<MutationRuleSpec>,
        info: MetamodelInfo,
        metamodelPath: String,
        prefix: String,
        accumulator: MutableMap<String, GeneratedMutation>
    ) {
        for (spec in specs) {
            if (!info.classNames().contains(spec.node)) {
                log.warn("MutationRuleGenerator: unknown class '${spec.node}' – skipping spec")
                continue
            }

            val repairMap = specsGenerator.getRepairsForRuleSpec(spec, info)

            for ((_, repairSpecs) in repairMap) {
                for (repairSpec in repairSpecs) {
                    emitMutation(repairSpec, info, metamodelPath, prefix, accumulator)
                }
            }
        }
    }

    /**
     * Translates a single [RepairSpec] into one or more [GeneratedMutation] objects and adds them
     * to [accumulator] (skipping names that are already present).
     *
     * CREATE specs are special: they may expand into multiple rules, one per containment context.
     * All other spec types produce exactly one rule.
     */
    private fun emitMutation(
        spec: RepairSpec,
        info: MetamodelInfo,
        metamodelPath: String,
        prefix: String,
        accumulator: MutableMap<String, GeneratedMutation>
    ) {
        when (spec.type) {
            RepairSpecType.CREATE -> {
                val contexts = info.containmentContextsFor(spec.className)
                if (contexts.isEmpty()) {
                    // Standalone create (no containment context)
                    val name = prefix + MutationRuleNameGenerator.forNode("CREATE", spec.className)
                    addIfAbsent(name, spec, metamodelPath, info, null, accumulator)
                } else {
                    for ((containerClass, refName) in contexts) {
                        val name = prefix + MutationRuleNameGenerator.forNodeCreate(
                            spec.className, containerClass, refName
                        )
                        addIfAbsent(name, spec, metamodelPath, info, containerClass to refName, accumulator)
                    }
                }
            }

            else -> {
                val name = prefix + MutationRuleNameGenerator.fromRepairSpec(spec)
                addIfAbsent(name, spec, metamodelPath, info, null, accumulator)
            }
        }
    }

    private fun addIfAbsent(
        name: String,
        spec: RepairSpec,
        metamodelPath: String,
        info: MetamodelInfo,
        createContext: Pair<String, String>?,
        accumulator: MutableMap<String, GeneratedMutation>
    ) {
        if (accumulator.containsKey(name)) return
        try {
            val ast = MutationAstBuilder.build(name, spec, metamodelPath, info, createContext)
            accumulator[name] = GeneratedMutation(name, ast)
        } catch (e: Exception) {
            log.warn("MutationRuleGenerator: failed to build rule '$name': ${e.message}")
        }
    }
}
