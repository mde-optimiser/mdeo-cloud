package com.mdeo.modeltransformation.runtime.match.plan

import com.mdeo.expression.ast.expressions.TypedIntLiteralExpression
import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.AssociationEndData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.modeltransformation.ast.EdgeLabelUtils
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLink
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkEnd
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternPropertyAssignment
import com.mdeo.modeltransformation.graph.ModelStatistics
import com.mdeo.modeltransformation.runtime.match.ExpressionNodeAnalyzer
import com.mdeo.modeltransformation.runtime.match.PatternCategories
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for model-sensitive (cost-based) structural ordering in [MatchPlanBuilder].
 *
 * The metamodel under test is a reduced version of the shape that made the IHTC operators
 * pathological: one singleton configuration object carrying an enum guard, hanging off a
 * singleton root, next to a class with hundreds of instances.
 *
 * ```
 * class Hospital {}
 * class Config {}
 * class State { phase: int }
 * class Shift {}
 * class Assignment {}
 *
 * Hospital.config[0..1]      <>-> Config.hospital[1]
 * Config.state[0..1]         <>-> State.config[1]
 * Hospital.shifts[0..*]      <>-> Shift.hospital[1]
 * Hospital.assignments[0..*] <>-> Assignment.hospital[1]
 * Shift.assignments[0..*]    <--> Assignment.shift[0..1]
 * ```
 *
 * The purely structural planner ranks `Hospital` highest (it is the pseudo-composition root)
 * and `State` lowest (a childless leaf two hops away), so the guard on `State.phase` ends up
 * last — after the `Shift` walk has already produced hundreds of partial matches. With
 * statistics available the planner instead starts wherever the model is smallest.
 */
class CostBasedMatchPlanOrderingTest {

    private val metamodel = MetamodelData(
        path = "/test/hospital.mm",
        classes = listOf(
            ClassData(name = "Hospital", isAbstract = false),
            ClassData(name = "Config", isAbstract = false),
            ClassData(name = "State", isAbstract = false),
            ClassData(name = "Shift", isAbstract = false),
            ClassData(name = "Assignment", isAbstract = false)
        ),
        associations = listOf(
            AssociationData(
                source = AssociationEndData("Hospital", "config", MultiplicityData.optional()),
                operator = "<>->",
                target = AssociationEndData("Config", "hospital", MultiplicityData.single())
            ),
            AssociationData(
                source = AssociationEndData("Config", "state", MultiplicityData.optional()),
                operator = "<>->",
                target = AssociationEndData("State", "config", MultiplicityData.single())
            ),
            AssociationData(
                source = AssociationEndData("Hospital", "shifts", MultiplicityData.many()),
                operator = "<>->",
                target = AssociationEndData("Shift", "hospital", MultiplicityData.single())
            ),
            AssociationData(
                source = AssociationEndData("Hospital", "assignments", MultiplicityData.many()),
                operator = "<>->",
                target = AssociationEndData("Assignment", "hospital", MultiplicityData.single())
            ),
            AssociationData(
                source = AssociationEndData("Shift", "assignments", MultiplicityData.many()),
                operator = "<-->",
                target = AssociationEndData("Assignment", "shift", MultiplicityData.optional())
            )
        )
    )

    // ── Cost model unit tests ─────────────────────────────────────────────────

    @Nested
    inner class CostModelTests {

        private fun costModel(statistics: ModelStatistics) = MatchCostModel(
            metamodelData = metamodel,
            statistics = statistics,
            nodeAnalyzer = ExpressionNodeAnalyzer(emptySet(), 0),
            isCollectionExpression = { false }
        )

        @Test
        fun `instance count includes subclasses because hasLabel admits subtypes`() {
            val withSubtypes = MetamodelData(
                classes = listOf(
                    ClassData("Vehicle", isAbstract = true),
                    ClassData("Car", isAbstract = false, extends = listOf("Vehicle")),
                    ClassData("Truck", isAbstract = false, extends = listOf("Vehicle"))
                )
            )
            val model = MatchCostModel(
                metamodelData = withSubtypes,
                statistics = statisticsOf(vertices = mapOf("Car" to 7, "Truck" to 3)),
                nodeAnalyzer = ExpressionNodeAnalyzer(emptySet(), 0),
                isCollectionExpression = { false }
            )

            assertEquals(10.0, model.instanceCount("Vehicle"))
            assertEquals(7.0, model.instanceCount("Car"))
        }

        @Test
        fun `scan factor is the class size reduced by its constant property filters`() {
            val model = costModel(statisticsOf(vertices = mapOf("Shift" to 480)))
            val unfiltered = makeInstance(null, "shift", "Shift")
            val filtered = makeInstance(null, "shift", "Shift", properties = listOf(equalsFilter("day", 3)))

            assertEquals(480.0, model.scanFactor(unfiltered))
            assertEquals(480.0 * MatchCostModel.EQUALITY_SELECTIVITY, model.scanFactor(filtered), 1e-9)
        }

        @Test
        fun `scan factor of an empty class is exactly zero`() {
            val model = costModel(statisticsOf(vertices = mapOf("Shift" to 480)))
            assertEquals(0.0, model.scanFactor(makeInstance(null, "a", "Assignment")))
        }

        @Test
        fun `walk factor is the measured average out-degree`() {
            val model = costModel(
                statisticsOf(
                    vertices = mapOf("Hospital" to 1, "Shift" to 480),
                    edges = mapOf(edgeLabel("shifts", "hospital") to 480)
                )
            )
            val link = makeLink(null, "hospital", "shifts", "shift", "hospital")

            // Hospital → Shift: 480 edges spread over 1 Hospital.
            assertEquals(480.0, model.walkFactor(link, isReversed = false, toInstance = null))
        }

        @Test
        fun `walk factor along a to-one role is capped at one`() {
            // Deliberately inconsistent statistics: 960 edges over 480 Shifts would give an
            // average of 2 for Shift.hospital[1], a field that can hold only one value.
            val model = costModel(
                statisticsOf(
                    vertices = mapOf("Hospital" to 1, "Shift" to 480),
                    edges = mapOf(edgeLabel("shifts", "hospital") to 960)
                )
            )
            val link = makeLink(null, "hospital", "shifts", "shift", "hospital")

            // Shift → Hospital follows Shift.hospital[1], so the fan-out is capped at 1 …
            assertEquals(1.0, model.walkFactor(link, isReversed = true, toInstance = null))
            // … while Hospital → Shift follows Hospital.shifts[0..*] and is not capped.
            assertEquals(960.0, model.walkFactor(link, isReversed = false, toInstance = null))
        }

        @Test
        fun `link check selectivity is the probability that a vertex pair is connected`() {
            val model = costModel(
                statisticsOf(
                    vertices = mapOf("Shift" to 480, "Assignment" to 100),
                    edges = mapOf(edgeLabel("assignments", "shift") to 100)
                )
            )
            val link = makeLink(null, "shift", "assignments", "assignment", "shift")

            assertEquals(100.0 / (480.0 * 100.0), model.linkCheckSelectivity(link), 1e-12)
        }

        @Test
        fun `link check selectivity of an association with no edges is zero`() {
            val model = costModel(statisticsOf(vertices = mapOf("Shift" to 480, "Assignment" to 100)))
            val link = makeLink(null, "shift", "assignments", "assignment", "shift")

            assertEquals(0.0, model.linkCheckSelectivity(link))
        }
    }

    // ── Plan ordering tests ───────────────────────────────────────────────────

    @Nested
    inner class OrderingTests {

        /**
         * ```
         * hospital : Hospital {}
         * config   : Config {}
         * state    : State { phase == 1 }
         * shift    : Shift {}
         * hospital.config -- config
         * config.state    -- state
         * hospital.shifts -- shift
         * ```
         */
        private fun guardPattern() = PatternCategories(
            matchableInstances = listOf(
                makeInstance(null, "hospital", "Hospital"),
                makeInstance(null, "config", "Config"),
                makeInstance(null, "state", "State", properties = listOf(equalsFilter("phase", 1))),
                makeInstance(null, "shift", "Shift")
            ),
            matchableLinks = listOf(
                makeLink(null, "hospital", "config", "config", "hospital"),
                makeLink(null, "config", "state", "state", "config"),
                makeLink(null, "hospital", "shifts", "shift", "hospital")
            ),
            createInstances = emptyList(), deleteInstances = emptyList(),
            createLinks = emptyList(), deleteLinks = emptyList(),
            variables = emptyList(), whereClauses = emptyList()
        )

        private val guardStatistics = statisticsOf(
            vertices = mapOf("Hospital" to 1, "Config" to 1, "State" to 1, "Shift" to 480),
            edges = mapOf(
                edgeLabel("config", "hospital") to 1,
                edgeLabel("state", "config") to 1,
                edgeLabel("shifts", "hospital") to 480
            )
        )

        @Test
        fun `singleton guard is evaluated before the large scan when statistics are available`() {
            val steps = buildPlan(guardPattern(), guardStatistics).baseSteps

            val guardIdx = steps.indexOfFirst {
                it is BaseStep.InlinePropertyConstraint && it.instanceName == "state"
            }
            val shiftIdx = coverageIndex(steps, "shift")

            assertTrue(guardIdx >= 0, "the state.phase filter must be present")
            assertTrue(shiftIdx >= 0, "shift must be covered")
            assertTrue(
                guardIdx < shiftIdx,
                "state.phase (idx=$guardIdx) must be checked before the 480-vertex Shift scan " +
                    "(idx=$shiftIdx)"
            )
            assertEquals("state", (steps.first() as BaseStep.VertexScan).instanceName,
                "the plan must open on the most selective instance")
        }

        @Test
        fun `without statistics the guard stays behind the large walk`() {
            // Documents the behaviour the cost model replaces, and pins the fallback path:
            // the pseudo-composition hierarchy is walked top-down, so the childless leaf that
            // carries the guard is covered last — after the 480-vertex fan-out.
            val steps = buildPlan(guardPattern(), statistics = null).baseSteps

            val guardIdx = steps.indexOfFirst {
                it is BaseStep.InlinePropertyConstraint && it.instanceName == "state"
            }
            val shiftIdx = coverageIndex(steps, "shift")

            assertTrue(guardIdx >= 0 && shiftIdx >= 0, "both steps must be present")
            assertTrue(guardIdx > shiftIdx,
                "legacy ordering is expected to check the guard (idx=$guardIdx) only after " +
                    "shift is covered (idx=$shiftIdx)")
        }

        @Test
        fun `an empty class is scanned first because it empties the traverser stream`() {
            val elements = PatternCategories(
                matchableInstances = listOf(
                    makeInstance(null, "hospital", "Hospital"),
                    makeInstance(null, "state", "State", properties = listOf(equalsFilter("phase", 1))),
                    makeInstance(null, "assignment", "Assignment")
                ),
                matchableLinks = listOf(
                    makeLink(null, "hospital", "state", "state", "hospital"),
                    makeLink(null, "hospital", "assignments", "assignment", "hospital")
                ),
                createInstances = emptyList(), deleteInstances = emptyList(),
                createLinks = emptyList(), deleteLinks = emptyList(),
                variables = emptyList(), whereClauses = emptyList()
            )
            val statistics = statisticsOf(
                vertices = mapOf("Hospital" to 1, "State" to 1, "Assignment" to 0),
                edges = mapOf(edgeLabel("state", "hospital") to 1)
            )

            val steps = buildPlan(elements, statistics).baseSteps

            assertEquals("assignment", (steps.first() as BaseStep.VertexScan).instanceName,
                "a class with no instances is the cheapest possible first step")
        }

        @Test
        fun `a class with no instances is preferred over the composition root`() {
            // Hospital is the composition root and therefore has the highest structural
            // priority, but scanning a class the model has no instances of ends the
            // traversal immediately, so it wins.
            val elements = PatternCategories(
                matchableInstances = listOf(
                    makeInstance(null, "hospital", "Hospital"),
                    makeInstance(null, "shift", "Shift")
                ),
                matchableLinks = listOf(makeLink(null, "hospital", "shifts", "shift", "hospital")),
                createInstances = emptyList(), deleteInstances = emptyList(),
                createLinks = emptyList(), deleteLinks = emptyList(),
                variables = emptyList(), whereClauses = emptyList()
            )
            val statistics = statisticsOf(vertices = mapOf("Hospital" to 1000, "Shift" to 0))

            val steps = buildPlan(elements, statistics).baseSteps

            assertEquals("shift", (steps.first() as BaseStep.VertexScan).instanceName,
                "the empty class must be scanned before the 1 000-vertex composition root")
        }

        @Test
        fun `a merely smaller class does not override the structural order`() {
            // Shift is 500x smaller, but scanning it still multiplies the partial-match set
            // rather than shrinking it, so there is no dominance argument for reordering and
            // the planner keeps its pre-existing choice. See candidateComparator.
            val elements = PatternCategories(
                matchableInstances = listOf(
                    makeInstance(null, "hospital", "Hospital"),
                    makeInstance(null, "shift", "Shift")
                ),
                matchableLinks = listOf(makeLink(null, "hospital", "shifts", "shift", "hospital")),
                createInstances = emptyList(), deleteInstances = emptyList(),
                createLinks = emptyList(), deleteLinks = emptyList(),
                variables = emptyList(), whereClauses = emptyList()
            )
            val statistics = statisticsOf(
                vertices = mapOf("Hospital" to 1000, "Shift" to 2),
                edges = mapOf(edgeLabel("shifts", "hospital") to 2)
            )

            val withStatistics = buildPlan(elements, statistics).baseSteps
            val withoutStatistics = buildPlan(elements, statistics = null).baseSteps

            assertEquals(withoutStatistics, withStatistics,
                "with no strictly-shrinking candidate the plan must match the legacy plan")
        }

        @Test
        fun `plan construction is deterministic for identical statistics`() {
            val first = buildPlan(guardPattern(), guardStatistics).baseSteps
            val second = buildPlan(guardPattern(), guardStatistics).baseSteps
            assertEquals(first, second)
        }
    }

    // ── Entry-point selection ─────────────────────────────────────────────────

    /**
     * The entry scan is the one step re-run for every incoming traverser, so which class it
     * reads decides the size of everything downstream. These tests pin that a metamodel
     * multiplicity may not move it.
     *
     * A `1`-multiplicity end says at most one neighbour exists *per source vertex*; it says
     * nothing about how many vertices the class has in total. A pass that demoted the scan
     * to the 1-side on that basis moved the IHTC nurse operators' entry scan from `Room`
     * onto `HospitalisationShift` and from `Nurse` onto `RoomShiftAssignment`, replacing a
     * handful of candidates with hundreds — four such swaps in one pattern, compounding into
     * an entry cross-product large enough to push a single match attempt past the worker
     * timeout. Cardinalities decide, or the structural order stands.
     */
    @Nested
    inner class EntryPointSelectionTests {

        /** `hospital -- shift`, with the two class sizes supplied by the caller. */
        private fun hospitalShiftPattern() = PatternCategories(
            matchableInstances = listOf(
                makeInstance(null, "hospital", "Hospital"),
                makeInstance(null, "shift", "Shift")
            ),
            matchableLinks = listOf(makeLink(null, "hospital", "shifts", "shift", "hospital")),
            createInstances = emptyList(), deleteInstances = emptyList(),
            createLinks = emptyList(), deleteLinks = emptyList(),
            variables = emptyList(), whereClauses = emptyList()
        )

        @Test
        fun `the smaller class is scanned even though the other end has multiplicity one`() {
            // Shift.hospital is the 1-side, but there are 480 Shifts and one Hospital.
            val steps = buildPlan(
                hospitalShiftPattern(),
                statisticsOf(vertices = mapOf("Hospital" to 1, "Shift" to 480))
            ).baseSteps

            assertEquals("hospital", (steps.first() as BaseStep.VertexScan).instanceName,
                "the single Hospital is the cheaper entry point")
            assertTrue(
                steps.any { it is BaseStep.EdgeWalk && it.toInstanceName == "shift" },
                "shift must be reached by walking, not by a second scan"
            )
        }

        @Test
        fun `an instance reachable by a walk is never covered by a second scan`() {
            // shift and assignment are both linked to hospital, and to each other. Whichever
            // the planner starts from, the remaining two are reachable — covering either by
            // scanning its class would re-read the whole extent to produce a subset of what
            // the walk yields, and would leave the link to be verified afterwards.
            val elements = PatternCategories(
                matchableInstances = listOf(
                    makeInstance(null, "hospital", "Hospital"),
                    makeInstance(null, "shift", "Shift"),
                    makeInstance(null, "assignment", "Assignment")
                ),
                matchableLinks = listOf(
                    makeLink(null, "hospital", "shifts", "shift", "hospital"),
                    makeLink(null, "hospital", "assignments", "assignment", "hospital"),
                    makeLink(null, "shift", "assignments", "assignment", "shift")
                ),
                createInstances = emptyList(), deleteInstances = emptyList(),
                createLinks = emptyList(), deleteLinks = emptyList(),
                variables = emptyList(), whereClauses = emptyList()
            )
            val steps = buildPlan(
                elements,
                statisticsOf(
                    vertices = mapOf("Hospital" to 1, "Shift" to 480, "Assignment" to 250),
                    edges = mapOf(
                        edgeLabel("shifts", "hospital") to 480,
                        edgeLabel("assignments", "hospital") to 250,
                        edgeLabel("assignments", "shift") to 250
                    )
                )
            ).baseSteps

            assertEquals(1, steps.count { it is BaseStep.VertexScan },
                "exactly one scan: everything else in a connected pattern is walkable")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildPlan(elements: PatternCategories, statistics: ModelStatistics?): MatchPlan {
        val names = elements.matchableInstances.map { it.objectInstance.name }.toSet()
        return MatchPlanBuilder(
            getVertexId = { null },
            nodeAnalyzer = ExpressionNodeAnalyzer(names, 0),
            isCollectionExpression = { false },
            metamodelData = metamodel,
            statistics = statistics
        ).build(elements, emptySet())
    }

    /** Returns the index of the step that covers [name], by scan or by walk. */
    private fun coverageIndex(steps: List<BaseStep>, name: String): Int = steps.indexOfFirst {
        (it is BaseStep.VertexScan && it.instanceName == name) ||
            (it is BaseStep.EdgeWalk && it.toInstanceName == name)
    }

    private fun edgeLabel(sourceRole: String, targetRole: String) =
        EdgeLabelUtils.computeEdgeLabel(sourceRole, targetRole)

    private fun statisticsOf(
        vertices: Map<String, Int> = emptyMap(),
        edges: Map<String, Int> = emptyMap()
    ): ModelStatistics = object : ModelStatistics {
        override val vertexCount: Int = vertices.values.sum()
        override val edgeCount: Int = edges.values.sum()
        override fun verticesWithLabel(label: String): Int = vertices[label] ?: 0
        override fun edgesWithLabel(edgeLabel: String): Int = edges[edgeLabel] ?: 0
    }

    private fun makeInstance(
        modifier: String?,
        name: String,
        className: String,
        properties: List<TypedPatternPropertyAssignment> = emptyList()
    ) = TypedPatternObjectInstanceElement(
        objectInstance = TypedPatternObjectInstance(
            modifier = modifier, name = name, className = className, properties = properties
        )
    )

    private fun equalsFilter(propertyName: String, value: Int) = TypedPatternPropertyAssignment(
        propertyName = propertyName,
        operator = "==",
        value = TypedIntLiteralExpression(evalType = 0, value = value.toString())
    )

    private fun makeLink(
        modifier: String?,
        sourceName: String, sourceProp: String?,
        targetName: String, targetProp: String?
    ) = TypedPatternLinkElement(
        link = TypedPatternLink(
            modifier = modifier,
            source = TypedPatternLinkEnd(objectName = sourceName, propertyName = sourceProp),
            target = TypedPatternLinkEnd(objectName = targetName, propertyName = targetProp)
        )
    )
}
