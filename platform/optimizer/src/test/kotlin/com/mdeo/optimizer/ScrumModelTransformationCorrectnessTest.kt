package com.mdeo.optimizer

import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.VoidType
import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.AssociationEndData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.metamodel.data.ModelDataLink
import com.mdeo.metamodel.data.ModelDataPropertyValue
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.metamodel.data.PropertyData
import com.mdeo.modeltransformation.ast.TypedAst as TransformationTypedAst
import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLink
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkEnd
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.graph.ModelGraph
import com.mdeo.modeltransformation.graph.mdeo.MdeoModelGraph
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import com.mdeo.optimizer.operators.TransformationAttemptRunner
import com.mdeo.optimizer.solution.Solution
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Correctness tests for model transformation nondeterminism in the Scrum domain.
 *
 * Verifies that ALL valid transformation matches are discoverable (i.e., the search
 * space is correctly and fully explored) by running each transformation [NUM_TRIALS]
 * times on independent deep-copies of a fixed initial model and checking coverage
 * of every expected variant.
 *
 * Tests also confirm that nondeterminism is active for the **very first match** on a
 * freshly created solution graph: each trial starts from a deep-copy of the same
 * initial state, so the only source of randomness is the engine's internal
 * `resetNondeterminism()` call (vertex-order shuffle) that happens before each
 * match execution inside [TransformationAttemptRunner].  This is exercised
 * independently for both the [GraphBackend.TINKER] and [GraphBackend.MDEO] backends.
 *
 * ## Initial model
 * - 2 stakeholders, 10 work items (workitem0–workitem9)
 * - 3 sprints with committed items:
 *   - sprint0 → workitem0, workitem1
 *   - sprint1 → workitem2, workitem3
 *   - sprint2 → workitem4
 * - 2 empty sprints (eligible for deletion): sprint3, sprint4
 * - 5 unassigned work items: workitem5–workitem9
 *
 * ## Transformations and expected variant counts
 * | Transformation         | Variants | Explanation                                     |
 * |------------------------|----------|-------------------------------------------------|
 * | deleteSprint           |        2 | delete sprint3 OR sprint4                       |
 * | createSprint           |        5 | one of 5 unassigned items assigned to new sprint|
 * | addItemToSprint        |       25 | 5 sprints × 5 unassigned items                  |
 * | moveItemBetweenSprints |       20 | 5 assigned items × 4 destination sprints each   |
 *
 * ## Coverage probability
 * With [NUM_TRIALS] = 300 trials the probability of missing any single variant is at
 * most `k · (1 − 1/k)^n` per transformation:
 * - deleteSprint (k=2):           ≈ 0%   (essentially impossible to miss)
 * - createSprint (k=5):           ≈ 0%
 * - addItemToSprint (k=25):       ≈ 0.012%  ← dominant term
 * - moveItemBetweenSprints (k=20):≈ 0.0004%
 *
 * Each test is retried up to [MAX_RETRIES] = 3 times before failing.  The probability
 * of all 3 attempts failing for addItemToSprint is roughly (0.00012)^3 ≈ 1.7 × 10⁻¹².
 * On failure, a detailed report lists every missing variant.
 */
class ScrumModelTransformationCorrectnessTest {

    /** Number of independent trials to run per transformation per backend. */
    private val NUM_TRIALS = 300

    /** Maximum retry attempts before a test is declared failed. */
    private val MAX_RETRIES = 3

    // ─────────────────────────────────────────────────────────────────────────
    // Graph backend abstraction
    // ─────────────────────────────────────────────────────────────────────────

    enum class GraphBackend {
        TINKER, MDEO;

        fun createModelGraph(modelData: ModelData, metamodel: Metamodel): ModelGraph = when (this) {
            TINKER -> TinkerModelGraph.create(modelData, metamodel)
            MDEO -> MdeoModelGraph.create(modelData, metamodel)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Model fixture constants
    // ─────────────────────────────────────────────────────────────────────────

    /** The two sprints that have no committed items and are eligible for deletion. */
    private val EMPTY_SPRINTS = setOf("sprint3", "sprint4")

    /** Work items that are not assigned to any sprint in the initial model. */
    private val UNASSIGNED_ITEMS = setOf("workitem5", "workitem6", "workitem7", "workitem8", "workitem9")

    /** Work items that ARE assigned and their current sprint. */
    private val ASSIGNED_ITEMS = mapOf(
        "workitem0" to "sprint0",
        "workitem1" to "sprint0",
        "workitem2" to "sprint1",
        "workitem3" to "sprint1",
        "workitem4" to "sprint2"
    )

    /** All five sprints that exist in the initial model. */
    private val ALL_SPRINTS = setOf("sprint0", "sprint1", "sprint2", "sprint3", "sprint4")

    // ─────────────────────────────────────────────────────────────────────────
    // Expected variant sets
    // ─────────────────────────────────────────────────────────────────────────

    /** 2 variants: which empty sprint was deleted. */
    private val EXPECTED_DELETE_VARIANTS: Set<String> = EMPTY_SPRINTS

    /** 5 variants: which unassigned workitem was assigned to the newly created sprint. */
    private val EXPECTED_CREATE_VARIANTS: Set<String> = UNASSIGNED_ITEMS

    /**
     * 25 variants: (sprint, workitem) pair added by addItemToSprint.
     * Any of the 5 existing sprints × any of the 5 unassigned items.
     */
    private val EXPECTED_ADD_VARIANTS: Set<Pair<String, String>> =
        ALL_SPRINTS.flatMap { sprint -> UNASSIGNED_ITEMS.map { item -> sprint to item } }.toSet()

    /**
     * 20 variants: (workitem, fromSprint, toSprint) for moveItemBetweenSprints.
     * Each of the 5 assigned items can move to any of the 4 other sprints.
     */
    private val EXPECTED_MOVE_VARIANTS: Set<Triple<String, String, String>> =
        ASSIGNED_ITEMS.flatMap { (item, fromSprint) ->
            (ALL_SPRINTS - fromSprint).map { toSprint -> Triple(item, fromSprint, toSprint) }
        }.toSet()

    // ─────────────────────────────────────────────────────────────────────────
    // Metamodel and model data builders
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildMetamodelData(): MetamodelData = MetamodelData(
        path = "/metamodel.mm",
        classes = listOf(
            ClassData(name = "Sprint", isAbstract = false, extends = emptyList(), properties = emptyList()),
            ClassData(name = "Stakeholder", isAbstract = false, extends = emptyList(), properties = emptyList()),
            ClassData(
                name = "WorkItem", isAbstract = false, extends = emptyList(),
                properties = listOf(
                    PropertyData(name = "importance", primitiveType = "int", multiplicity = MultiplicityData(lower = 1, upper = 1)),
                    PropertyData(name = "effort", primitiveType = "int", multiplicity = MultiplicityData(lower = 1, upper = 1))
                )
            )
        ),
        enums = emptyList(),
        associations = listOf(
            AssociationData(
                source = AssociationEndData(className = "Stakeholder", multiplicity = MultiplicityData(lower = 1, upper = -1), name = "workitems"),
                operator = "<-->",
                target = AssociationEndData(className = "WorkItem", multiplicity = MultiplicityData(lower = 1, upper = 1), name = "stakeholder")
            ),
            AssociationData(
                source = AssociationEndData(className = "Sprint", multiplicity = MultiplicityData(lower = 1, upper = -1), name = "committedItems"),
                operator = "<-->",
                target = AssociationEndData(className = "WorkItem", multiplicity = MultiplicityData(lower = 1, upper = 1), name = "isPlannedFor")
            )
        ),
        importedMetamodelPaths = emptyList()
    )

    /**
     * Builds the initial model:
     * - stakeholder0 owns workitem0–4 (all assigned to sprints)
     * - stakeholder1 owns workitem5–9 (all unassigned)
     * - sprint0: workitem0, workitem1
     * - sprint1: workitem2, workitem3
     * - sprint2: workitem4
     * - sprint3: empty
     * - sprint4: empty
     */
    private fun buildModelData(): ModelData {
        val instances = mutableListOf<ModelDataInstance>()
        instances += ModelDataInstance(name = "stakeholder0", className = "Stakeholder", properties = emptyMap())
        instances += ModelDataInstance(name = "stakeholder1", className = "Stakeholder", properties = emptyMap())
        for (i in 0..9) {
            instances += ModelDataInstance(
                name = "workitem$i", className = "WorkItem",
                properties = mapOf(
                    "importance" to ModelDataPropertyValue.NumberValue(3.0),
                    "effort" to ModelDataPropertyValue.NumberValue(3.0)
                )
            )
        }
        for (i in 0..4) {
            instances += ModelDataInstance(name = "sprint$i", className = "Sprint", properties = emptyMap())
        }

        val links = mutableListOf<ModelDataLink>()
        // Stakeholder ownership
        for (i in 0..4) {
            links += ModelDataLink("stakeholder0", "workitems", "workitem$i", "stakeholder")
        }
        for (i in 5..9) {
            links += ModelDataLink("stakeholder1", "workitems", "workitem$i", "stakeholder")
        }
        // Sprint assignments: sprint0→wi0,wi1 | sprint1→wi2,wi3 | sprint2→wi4
        links += ModelDataLink("sprint0", "committedItems", "workitem0", "isPlannedFor")
        links += ModelDataLink("sprint0", "committedItems", "workitem1", "isPlannedFor")
        links += ModelDataLink("sprint1", "committedItems", "workitem2", "isPlannedFor")
        links += ModelDataLink("sprint1", "committedItems", "workitem3", "isPlannedFor")
        links += ModelDataLink("sprint2", "committedItems", "workitem4", "isPlannedFor")

        return ModelData(metamodelPath = "../metamodel.mm", instances = instances, links = links)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Transformation AST builders (identical to ScrumOptimizationPerformanceTestBase)
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildTypes(): List<ReturnType> = listOf(
        VoidType(),
        ClassTypeRef(`package` = "builtin", type = "string", isNullable = false, typeArgs = emptyMap()),
        ClassTypeRef(`package` = "builtin", type = "double", isNullable = false, typeArgs = emptyMap()),
        ClassTypeRef(`package` = "builtin", type = "boolean", isNullable = false, typeArgs = emptyMap()),
        ClassTypeRef(`package` = "builtin", type = "Any", isNullable = true, typeArgs = emptyMap())
    )

    private fun buildTransformations(): Map<String, TransformationTypedAst> {
        val types = buildTypes()

        // ── createSprint ──────────────────────────────────────────────────────
        // Match an unassigned WorkItem (no Sprint currently has it committed) and its
        // Stakeholder, then CREATE a new Sprint linked to that WorkItem.
        // Variant = which unassigned WorkItem is chosen (5 choices: workitem5–9).
        val createSprintAst = TransformationTypedAst(
            types = types,
            metamodelPath = "/metamodel.mm",
            statements = listOf(
                TypedMatchStatement(
                    pattern = TypedPattern(
                        elements = listOf(
                            TypedPatternObjectInstanceElement(
                                objectInstance = TypedPatternObjectInstance(
                                    modifier = "create", name = "newSprint", className = "Sprint", properties = emptyList()
                                )
                            ),
                            TypedPatternObjectInstanceElement(
                                objectInstance = TypedPatternObjectInstance(
                                    modifier = null, name = "workitem", className = "WorkItem", properties = emptyList()
                                )
                            ),
                            TypedPatternObjectInstanceElement(
                                objectInstance = TypedPatternObjectInstance(
                                    modifier = null, name = "newStakeholder", className = "Stakeholder", properties = emptyList()
                                )
                            ),
                            TypedPatternObjectInstanceElement(
                                objectInstance = TypedPatternObjectInstance(
                                    modifier = "forbid", name = "newSprint1", className = "Sprint", properties = emptyList()
                                )
                            ),
                            TypedPatternLinkElement(
                                link = TypedPatternLink(
                                    modifier = "create",
                                    source = TypedPatternLinkEnd(objectName = "newSprint", propertyName = "committedItems"),
                                    target = TypedPatternLinkEnd(objectName = "workitem", propertyName = "isPlannedFor")
                                )
                            ),
                            TypedPatternLinkElement(
                                link = TypedPatternLink(
                                    modifier = "forbid",
                                    source = TypedPatternLinkEnd(objectName = "newSprint1", propertyName = "committedItems"),
                                    target = TypedPatternLinkEnd(objectName = "workitem", propertyName = "isPlannedFor")
                                )
                            ),
                            TypedPatternLinkElement(
                                link = TypedPatternLink(
                                    modifier = null,
                                    source = TypedPatternLinkEnd(objectName = "newStakeholder", propertyName = "workitems"),
                                    target = TypedPatternLinkEnd(objectName = "workitem", propertyName = "stakeholder")
                                )
                            )
                        )
                    )
                )
            )
        )

        // ── addItemToSprint ───────────────────────────────────────────────────
        // Match any Sprint and any unassigned WorkItem, CREATE the committedItems link.
        // NAC 1 (forbid link sprint1→workItem): prevents assigning already-assigned item.
        // NAC 2 (forbid sprint2→workItem): no OTHER sprint may already have the item.
        // Variant = (sprint, workItem) — 5 sprints × 5 unassigned items = 25 combinations.
        val addItemToSprintAst = TransformationTypedAst(
            types = types,
            metamodelPath = "/metamodel.mm",
            statements = listOf(
                TypedMatchStatement(
                    pattern = TypedPattern(
                        elements = listOf(
                            TypedPatternObjectInstanceElement(
                                objectInstance = TypedPatternObjectInstance(
                                    modifier = null, name = "sprint1", className = "Sprint", properties = emptyList()
                                )
                            ),
                            TypedPatternObjectInstanceElement(
                                objectInstance = TypedPatternObjectInstance(
                                    modifier = null, name = "workItem", className = "WorkItem", properties = emptyList()
                                )
                            ),
                            TypedPatternObjectInstanceElement(
                                objectInstance = TypedPatternObjectInstance(
                                    modifier = "forbid", name = "sprint2", className = "Sprint", properties = emptyList()
                                )
                            ),
                            TypedPatternLinkElement(
                                link = TypedPatternLink(
                                    modifier = "create",
                                    source = TypedPatternLinkEnd(objectName = "sprint1", propertyName = "committedItems"),
                                    target = TypedPatternLinkEnd(objectName = "workItem", propertyName = "isPlannedFor")
                                )
                            ),
                            TypedPatternLinkElement(
                                link = TypedPatternLink(
                                    modifier = "forbid",
                                    source = TypedPatternLinkEnd(objectName = "sprint1", propertyName = "committedItems"),
                                    target = TypedPatternLinkEnd(objectName = "workItem", propertyName = "isPlannedFor")
                                )
                            ),
                            TypedPatternLinkElement(
                                link = TypedPatternLink(
                                    modifier = "forbid",
                                    source = TypedPatternLinkEnd(objectName = "sprint2", propertyName = "committedItems"),
                                    target = TypedPatternLinkEnd(objectName = "workItem", propertyName = "isPlannedFor")
                                )
                            )
                        )
                    )
                )
            )
        )

        // ── deleteSprint ──────────────────────────────────────────────────────
        // DELETE a Sprint that has no committed WorkItems (NAC ensures emptiness).
        // Variant = which empty sprint is deleted (2 choices: sprint3, sprint4).
        val deleteSprintAst = TransformationTypedAst(
            types = types,
            metamodelPath = "/metamodel.mm",
            statements = listOf(
                TypedMatchStatement(
                    pattern = TypedPattern(
                        elements = listOf(
                            TypedPatternObjectInstanceElement(
                                objectInstance = TypedPatternObjectInstance(
                                    modifier = "delete", name = "newSprint", className = "Sprint", properties = emptyList()
                                )
                            ),
                            TypedPatternObjectInstanceElement(
                                objectInstance = TypedPatternObjectInstance(
                                    modifier = "forbid", name = "newWorkItem", className = "WorkItem", properties = emptyList()
                                )
                            ),
                            TypedPatternLinkElement(
                                link = TypedPatternLink(
                                    modifier = "forbid",
                                    source = TypedPatternLinkEnd(objectName = "newSprint", propertyName = "committedItems"),
                                    target = TypedPatternLinkEnd(objectName = "newWorkItem", propertyName = "isPlannedFor")
                                )
                            )
                        )
                    )
                )
            )
        )

        // ── moveItemBetweenSprints ────────────────────────────────────────────
        // Match sprint2 (must HAVE the workItem via DELETE link) and sprint1 (destination).
        // CREATE link sprint1→workItem, DELETE link sprint2→workItem.
        // Variant = (workItem, sprint2=fromSprint, sprint1=toSprint).
        // 5 assigned items × 4 destination sprints = 20 combinations.
        val moveItemBetweenSprintsAst = TransformationTypedAst(
            types = types,
            metamodelPath = "/metamodel.mm",
            statements = listOf(
                TypedMatchStatement(
                    pattern = TypedPattern(
                        elements = listOf(
                            TypedPatternObjectInstanceElement(
                                objectInstance = TypedPatternObjectInstance(
                                    modifier = null, name = "sprint1", className = "Sprint", properties = emptyList()
                                )
                            ),
                            TypedPatternObjectInstanceElement(
                                objectInstance = TypedPatternObjectInstance(
                                    modifier = null, name = "sprint2", className = "Sprint", properties = emptyList()
                                )
                            ),
                            TypedPatternObjectInstanceElement(
                                objectInstance = TypedPatternObjectInstance(
                                    modifier = null, name = "workItem", className = "WorkItem", properties = emptyList()
                                )
                            ),
                            TypedPatternLinkElement(
                                link = TypedPatternLink(
                                    modifier = "create",
                                    source = TypedPatternLinkEnd(objectName = "sprint1", propertyName = "committedItems"),
                                    target = TypedPatternLinkEnd(objectName = "workItem", propertyName = "isPlannedFor")
                                )
                            ),
                            TypedPatternLinkElement(
                                link = TypedPatternLink(
                                    modifier = "delete",
                                    source = TypedPatternLinkEnd(objectName = "sprint2", propertyName = "committedItems"),
                                    target = TypedPatternLinkEnd(objectName = "workItem", propertyName = "isPlannedFor")
                                )
                            )
                        )
                    )
                )
            )
        )

        return mapOf(
            "/transformation/createSprint.mt" to createSprintAst,
            "/transformation/addItemToSprint.mt" to addItemToSprintAst,
            "/transformation/deleteSprint.mt" to deleteSprintAst,
            "/transformation/moveItemBetweenSprints.mt" to moveItemBetweenSprintsAst
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Model-difference analysis helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts all committedItems edges (Sprint → WorkItem) from a [ModelData].
     * These are the only edges that change under the four Scrum transformations.
     */
    private fun committedLinks(data: ModelData): List<ModelDataLink> =
        data.links.filter { it.sourceProperty == "committedItems" }

    /**
     * Returns the name of the Sprint that was deleted, or `null` if exactly one
     * deletion was not detectable (should not happen for a successful deleteSprint).
     */
    private fun detectDeletedSprint(initial: ModelData, result: ModelData): String? {
        val initialSprints = initial.instances.filter { it.className == "Sprint" }.map { it.name }.toSet()
        val resultSprints = result.instances.filter { it.className == "Sprint" }.map { it.name }.toSet()
        val deleted = initialSprints - resultSprints
        return deleted.singleOrNull()
    }

    /**
     * Returns the WorkItem name that received a new committedItems link from a freshly
     * created (unnamed) Sprint, or `null` if detection fails.
     *
     * Identification strategy: the targetName of any new committedItems link whose
     * sourceName was NOT present as a Sprint in the initial model.
     */
    private fun detectCreatedSprintItem(initial: ModelData, result: ModelData): String? {
        val initialSprintNames = initial.instances.filter { it.className == "Sprint" }.map { it.name }.toSet()
        val initialAssigned = committedLinks(initial).map { it.targetName }.toSet()
        val resultLinks = committedLinks(result)
        // A new link added from a Sprint that did NOT exist initially
        val newlyAssigned = resultLinks
            .filter { it.sourceName !in initialSprintNames && it.targetName !in initialAssigned }
            .map { it.targetName }
        return newlyAssigned.singleOrNull()
    }

    /**
     * Returns the (sprint, workItem) pair that was newly committed by addItemToSprint,
     * or `null` if detection fails.
     */
    private fun detectAddedSprintItem(initial: ModelData, result: ModelData): Pair<String, String>? {
        val initialLinks = committedLinks(initial).map { it.sourceName to it.targetName }.toSet()
        val resultLinks = committedLinks(result).map { it.sourceName to it.targetName }.toSet()
        val added = resultLinks - initialLinks
        return added.singleOrNull()
    }

    /**
     * Returns the (workItem, fromSprint, toSprint) triple describing the move performed
     * by moveItemBetweenSprints, or `null` if detection fails.
     */
    private fun detectMovedItem(initial: ModelData, result: ModelData): Triple<String, String, String>? {
        val initialByItem = committedLinks(initial).groupBy({ it.targetName }, { it.sourceName })
        val resultByItem = committedLinks(result).groupBy({ it.targetName }, { it.sourceName })
        for ((item, initialSprints) in initialByItem) {
            val resultSprints = resultByItem[item]?.toSet() ?: emptySet()
            val fromSprints = initialSprints.toSet() - resultSprints
            val toSprints = resultSprints - initialSprints.toSet()
            if (fromSprints.size == 1 && toSprints.size == 1) {
                return Triple(item, fromSprints.first(), toSprints.first())
            }
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Retry helper
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs [block] up to [maxAttempts] times.  Returns on the first successful
     * attempt; re-throws the last [AssertionError] if all attempts fail.
     */
    private fun runWithRetries(
        maxAttempts: Int = MAX_RETRIES,
        testName: String,
        block: () -> Unit
    ) {
        var lastError: AssertionError? = null
        for (attempt in 1..maxAttempts) {
            try {
                block()
                return
            } catch (e: AssertionError) {
                lastError = e
                if (attempt < maxAttempts) {
                    System.err.println(
                        "[$testName] Attempt $attempt/$maxAttempts failed (likely unlucky — retrying): ${e.message?.lines()?.firstOrNull()}"
                    )
                }
            }
        }
        throw lastError!!
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Failure report builder
    // ─────────────────────────────────────────────────────────────────────────

    private fun <T> buildFailureReport(
        transformationName: String,
        backend: GraphBackend,
        expected: Set<T>,
        found: Set<T>,
        failedApplications: Int
    ): String {
        val missing = expected - found
        return buildString {
            appendLine()
            appendLine("[$transformationName / $backend] COVERAGE FAILURE after $NUM_TRIALS trials (retried $MAX_RETRIES times)")
            appendLine("  Expected ${expected.size} variants, found ${found.size}, MISSING ${missing.size}")
            appendLine("  Transformation returned false (no match found): $failedApplications / $NUM_TRIALS trials")
            appendLine()
            appendLine("  ── Missing variants (never selected) ──────────────────")
            missing.forEach { appendLine("    ✗  $it") }
            appendLine()
            appendLine("  ── Found variants ─────────────────────────────────────")
            found.forEach { appendLine("    ✓  $it") }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tests
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that [deleteSprint] picks each of the two empty sprints at least once
     * across [NUM_TRIALS] independent trials.
     *
     * Expected variants (2): sprint3, sprint4.
     * P(miss in 300 trials) ≤ 2 × 0.5^300 ≈ 10⁻⁹⁰ — essentially impossible.
     */
    @ParameterizedTest(name = "deleteSprint covers all empty sprints [{0}]")
    @EnumSource(GraphBackend::class)
    fun `deleteSprint covers all empty sprints`(backend: GraphBackend) {
        val metamodel = Metamodel.compile(buildMetamodelData())
        val modelData = buildModelData()
        val transformations = buildTransformations()
        val runner = TransformationAttemptRunner(transformations)
        val initialSolution = Solution(backend.createModelGraph(modelData, metamodel))
        val initialData = initialSolution.modelGraph.toModelData()

        try {
            runWithRetries(testName = "deleteSprint [$backend]") {
                val found = mutableSetOf<String>()
                var failedCount = 0
                repeat(NUM_TRIALS) {
                    val copy = initialSolution.deepCopy()
                    try {
                        if (runner.tryApply(copy, "/transformation/deleteSprint.mt").isApplied) {
                            detectDeletedSprint(initialData, copy.modelGraph.toModelData())
                                ?.let { found += it }
                        } else {
                            failedCount++
                        }
                    } finally {
                        copy.close()
                    }
                }
                assertTrue(found.containsAll(EXPECTED_DELETE_VARIANTS)) {
                    buildFailureReport("deleteSprint", backend, EXPECTED_DELETE_VARIANTS, found, failedCount)
                }
            }
        } finally {
            initialSolution.close()
        }
    }

    /**
     * Verifies that [createSprint] picks each of the five unassigned work items at
     * least once across [NUM_TRIALS] independent trials.
     *
     * Expected variants (5): workitem5–workitem9.
     * P(miss in 300 trials) ≤ 5 × 0.8^300 ≈ 10⁻²⁸ — essentially impossible.
     */
    @ParameterizedTest(name = "createSprint covers all unassigned workitems [{0}]")
    @EnumSource(GraphBackend::class)
    fun `createSprint covers all unassigned workitems`(backend: GraphBackend) {
        val metamodel = Metamodel.compile(buildMetamodelData())
        val modelData = buildModelData()
        val transformations = buildTransformations()
        val runner = TransformationAttemptRunner(transformations)
        val initialSolution = Solution(backend.createModelGraph(modelData, metamodel))
        val initialData = initialSolution.modelGraph.toModelData()

        try {
            runWithRetries(testName = "createSprint [$backend]") {
                val found = mutableSetOf<String>()
                var failedCount = 0
                repeat(NUM_TRIALS) {
                    val copy = initialSolution.deepCopy()
                    try {
                        if (runner.tryApply(copy, "/transformation/createSprint.mt").isApplied) {
                            detectCreatedSprintItem(initialData, copy.modelGraph.toModelData())
                                ?.let { found += it }
                        } else {
                            failedCount++
                        }
                    } finally {
                        copy.close()
                    }
                }
                assertTrue(found.containsAll(EXPECTED_CREATE_VARIANTS)) {
                    buildFailureReport("createSprint", backend, EXPECTED_CREATE_VARIANTS, found, failedCount)
                }
            }
        } finally {
            initialSolution.close()
        }
    }

    /**
     * Verifies that [addItemToSprint] picks every (sprint, unassigned workitem) pair
     * at least once across [NUM_TRIALS] independent trials.
     *
     * Expected variants (25): 5 sprints × 5 unassigned work items.
     * P(miss in 300 trials) ≤ 25 × (24/25)^300 ≈ 0.012% per attempt.
     * P(miss in all 3 retries) ≈ (0.00012)^3 ≈ 10⁻¹².
     *
     * This is the hardest of the four tests and drives the choice of [NUM_TRIALS] = 300.
     */
    @ParameterizedTest(name = "addItemToSprint covers all sprint-workitem combinations [{0}]")
    @EnumSource(GraphBackend::class)
    fun `addItemToSprint covers all sprint-workitem combinations`(backend: GraphBackend) {
        val metamodel = Metamodel.compile(buildMetamodelData())
        val modelData = buildModelData()
        val transformations = buildTransformations()
        val runner = TransformationAttemptRunner(transformations)
        val initialSolution = Solution(backend.createModelGraph(modelData, metamodel))
        val initialData = initialSolution.modelGraph.toModelData()

        try {
            runWithRetries(testName = "addItemToSprint [$backend]") {
                val found = mutableSetOf<Pair<String, String>>()
                var failedCount = 0
                repeat(NUM_TRIALS) {
                    val copy = initialSolution.deepCopy()
                    try {
                        if (runner.tryApply(copy, "/transformation/addItemToSprint.mt").isApplied) {
                            detectAddedSprintItem(initialData, copy.modelGraph.toModelData())
                                ?.let { found += it }
                        } else {
                            failedCount++
                        }
                    } finally {
                        copy.close()
                    }
                }
                assertTrue(found.containsAll(EXPECTED_ADD_VARIANTS)) {
                    buildFailureReport("addItemToSprint", backend, EXPECTED_ADD_VARIANTS, found, failedCount)
                }
            }
        } finally {
            initialSolution.close()
        }
    }

    /**
     * Verifies that [moveItemBetweenSprints] picks every (workItem, fromSprint, toSprint)
     * combination at least once across [NUM_TRIALS] independent trials.
     *
     * Expected variants (20): each of the 5 assigned work items can move to any of
     * the 4 other sprints (injective matching ensures fromSprint ≠ toSprint).
     * P(miss in 300 trials) ≤ 20 × (19/20)^300 ≈ 0.0004%.
     */
    @ParameterizedTest(name = "moveItemBetweenSprints covers all valid moves [{0}]")
    @EnumSource(GraphBackend::class)
    fun `moveItemBetweenSprints covers all valid moves`(backend: GraphBackend) {
        val metamodel = Metamodel.compile(buildMetamodelData())
        val modelData = buildModelData()
        val transformations = buildTransformations()
        val runner = TransformationAttemptRunner(transformations)
        val initialSolution = Solution(backend.createModelGraph(modelData, metamodel))
        val initialData = initialSolution.modelGraph.toModelData()

        try {
            runWithRetries(testName = "moveItemBetweenSprints [$backend]") {
                val found = mutableSetOf<Triple<String, String, String>>()
                var failedCount = 0
                repeat(NUM_TRIALS) {
                    val copy = initialSolution.deepCopy()
                    try {
                        if (runner.tryApply(copy, "/transformation/moveItemBetweenSprints.mt").isApplied) {
                            detectMovedItem(initialData, copy.modelGraph.toModelData())
                                ?.let { found += it }
                        } else {
                            failedCount++
                        }
                    } finally {
                        copy.close()
                    }
                }
                assertTrue(found.containsAll(EXPECTED_MOVE_VARIANTS)) {
                    buildFailureReport("moveItemBetweenSprints", backend, EXPECTED_MOVE_VARIANTS, found, failedCount)
                }
            }
        } finally {
            initialSolution.close()
        }
    }
}
