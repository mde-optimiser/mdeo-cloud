package com.mdeo.optimizer.rulegen

import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.optimizer.config.RefinementConfig
import org.slf4j.LoggerFactory

/**
 * Entry point for auto-generating mutation operators from a metamodel description.
 *
 * Equivalent to RulesGenerator in the original mde_optimiser rulegen library, but
 * instead of producing Henshin Module objects it produces [GeneratedMutation] values
 * — each containing a unique rule name and a ready-to-run [TypedAst].
 *
 * ## Usage
 * ```kotlin
 * val mutations = MutationRuleGenerator.generate(
 *     metamodelData = metamodelData,
 *     specs = listOf(MutationRuleSpec(node = "Foo", action = MutationAction.ALL)),
 *     metamodelPath = "/path/to/metamodel.mm"
 * )
 * // mutations: List<GeneratedMutation>, each with a unique name and TypedAst
 * ```
 *
 * ## Two-pass generation
 * When [refinements] are provided the generator runs a second pass over the same
 * [specs] using a modified [MetamodelInfo] where the specified reference multiplicities
 * are replaced by the tightened bounds from each [RefinementConfig].  Rules produced
 * in the second pass are prefixed with `"S_"` (solution-space) to distinguish them from
 * the base `"P_"`-equivalent rules produced in the first pass.  After the second pass,
 * both sets are merged; any S-type rule whose name already exists in the result (because
 * the tightened bounds produce an identical rule shape) is silently skipped.
 *
 * ## Deduplication
 * The Java original uses `HenshinModuleAnalysis` (semantic rule equality) to remove
 * duplicate rules.  This port deduplicates by rule name instead — an identical
 * (node, edge, type) triple always produces the same name, so structural duplicates
 * collapse naturally.
 *
 * REPORT: Semantic deduplication (two structurally different rules that are
 * behaviourally equivalent) is not performed. Name-based deduplication covers all
 * cases produced by the current [SpecsGenerator] logic.
 */
object MutationRuleGenerator {

    private val logger = LoggerFactory.getLogger(MutationRuleGenerator::class.java)

    /**
     * Generates mutation rules for all [specs] against the given [metamodelData].
     *
     * When [refinements] is non-empty a second generation pass is performed using a
     * modified [MetamodelInfo] with the specified reference multiplicities tightened to
     * the lower/upper values in each [RefinementConfig].  Rules from the second pass
     * carry an `"S_"` name prefix and are merged into the result after the base rules.
     *
     * @param metamodelData The platform metamodel description to introspect.
     * @param specs         List of [MutationRuleSpec] entries describing what to generate.
     * @param metamodelPath Absolute file-system path of the metamodel (stored in each TypedAst).
     *                      Defaults to [MetamodelData.path].
     * @param refinements   Optional multiplicity refinements from the optimisation goal config.
     *                      When non-empty, triggers S-type rule generation.
     * @return Deduplicated list of [GeneratedMutation] values in stable order.
     */
    fun generate(
        metamodelData: MetamodelData,
        specs: List<MutationRuleSpec>,
        metamodelPath: String = metamodelData.path,
        refinements: List<RefinementConfig> = emptyList()
    ): List<GeneratedMutation> {
        if (specs.isEmpty()) {
            logger.info("MutationRuleGenerator: no specs provided, returning empty list")
            return emptyList()
        }

        val baseMetamodelInfo = MetamodelInfo(metamodelData)
        val specsGenerator = SpecsGenerator()

        val seen = LinkedHashMap<String, GeneratedMutation>()

        runPass(specs, baseMetamodelInfo, specsGenerator, metamodelPath, namePrefix = "", seen)

        if (refinements.isNotEmpty()) {
            val overrides = refinements.map { r ->
                MultiplicityOverride(
                    className = r.className,
                    refName = r.fieldName,
                    lower = r.lower,
                    upper = r.upper
                )
            }
            val refinedMetamodelInfo = MetamodelInfo.withOverrides(metamodelData, overrides)
            runPass(specs, refinedMetamodelInfo, specsGenerator, metamodelPath, namePrefix = "S_", seen)
        }

        logger.info("MutationRuleGenerator produced {} rule(s) from {} spec(s)", seen.size, specs.size)
        // Sort by name to guarantee a stable, deterministic order independent of metamodel
        // data ordering. This is required so that numerical operator indices (assigned by
        // MutationStrategyFactory based on sorted paths) are consistent across federated nodes
        // that may receive the generated rules via different serialisation paths.
        return seen.values.sortedBy { it.name }
    }

    /**
     * Executes one generation pass over [specs] using [metamodelInfo], inserting results
     * into [seen].  Rules are named with [namePrefix] prepended so that second-pass
     * (S-type) rules are distinguishable from base rules.
     */
    private fun runPass(
        specs: List<MutationRuleSpec>,
        metamodelInfo: MetamodelInfo,
        specsGenerator: SpecsGenerator,
        metamodelPath: String,
        namePrefix: String,
        seen: LinkedHashMap<String, GeneratedMutation>
    ) {
        for (spec in specs) {
            if (!metamodelInfo.hasClass(spec.node)) {
                logger.warn("Class '{}' not found in metamodel — skipping spec", spec.node)
                continue
            }

            val repairMap = specsGenerator.getRepairsForRuleSpec(spec, metamodelInfo)

            for ((_, repairList) in repairMap) {
                for (repairSpec in repairList) {
                    val ruleName = MutationRuleNameGenerator.fromRepairSpec(repairSpec, prefix = namePrefix)

                    if (seen.containsKey(ruleName)) {
                        logger.info("Skipping duplicate rule: {}", ruleName)
                        continue
                    }

                    val asts = buildAsts(ruleName, repairSpec, metamodelPath, metamodelInfo, namePrefix)
                    for ((name, typedAst) in asts) {
                        if (!seen.containsKey(name)) {
                            seen[name] = GeneratedMutation(name = name, typedAst = typedAst)
                        }
                    }
                }
            }
        }
    }

    /**
     * Builds one or more (name, TypedAst) pairs for [repairSpec].
     *
     * CREATE rules expand to one rule per containment context (or one standalone rule
     * when none exist).  The rule name is derived from the containment context rather
     * than [baseName], so multiple CREATE specs for the same node (different edge variants)
     * always collapse to the same set of context-specific or standalone names — the
     * deduplication in [runPass] then keeps only the first occurrence.
     *
     * Non-CREATE rules produce exactly one rule using [baseName].
     *
     * @param namePrefix Prefix to prepend to all generated names (e.g. `"S_"`).
     */
    private fun buildAsts(
        baseName: String,
        repairSpec: RepairSpec,
        metamodelPath: String,
        metamodelInfo: MetamodelInfo,
        namePrefix: String = ""
    ): List<Pair<String, com.mdeo.modeltransformation.ast.TypedAst>> {
        return when (repairSpec.type) {
            RepairSpecType.CREATE,
            RepairSpecType.CREATE_LB_REPAIR,
            RepairSpecType.CREATE_LB_REPAIR_MANY -> {
                val contexts = metamodelInfo.containmentContextsFor(repairSpec.nodeName)
                if (contexts.isEmpty()) {
                    val standaloneName = MutationRuleNameGenerator.forNode("CREATE", repairSpec.nodeName, namePrefix)
                    val ast = MutationAstBuilder.build(
                        standaloneName, repairSpec, metamodelPath, metamodelInfo, createContext = null
                    )
                    listOfNotNull(ast?.let { standaloneName to it })
                } else {
                    contexts.mapNotNull { (containerClass, containerRef) ->
                        val contextName = MutationRuleNameGenerator.forNodeCreate(
                            repairSpec.nodeName, containerClass, containerRef, namePrefix
                        )
                        val ast = MutationAstBuilder.build(
                            contextName, repairSpec, metamodelPath, metamodelInfo,
                            createContext = containerClass to containerRef
                        )
                        ast?.let { contextName to it }
                    }
                }
            }
            else -> {
                val ast = MutationAstBuilder.build(
                    baseName, repairSpec, metamodelPath, metamodelInfo
                )
                listOfNotNull(ast?.let { baseName to it })
            }
        }
    }
}

