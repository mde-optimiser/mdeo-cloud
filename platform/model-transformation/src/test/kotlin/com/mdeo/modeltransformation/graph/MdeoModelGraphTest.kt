package com.mdeo.modeltransformation.graph

import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.ModelInstance
import com.mdeo.metamodel.data.*
import com.mdeo.modeltransformation.ast.EdgeLabelUtils
import com.mdeo.modeltransformation.graph.mdeo.MdeoGraph
import com.mdeo.modeltransformation.graph.mdeo.MdeoModelGraph
import com.mdeo.modeltransformation.graph.mdeo.MdeoVertex
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Comprehensive tests for [MdeoModelGraph] and the underlying [MdeoGraph].
 *
 * Covers basic graph operations, comparison with [TinkerModelGraph],
 * deep copy, nondeterminism reset, ModelData round-trip, VertexRef tracking,
 * and ASM-generated property access on model instances.
 */
class MdeoModelGraphTest {

    private val metamodelData = MetamodelData(
        classes = listOf(
            ClassData(
                name = "House",
                isAbstract = false,
                extends = emptyList(),
                properties = listOf(
                    PropertyData(name = "address", primitiveType = "string", multiplicity = MultiplicityData.single()),
                    PropertyData(name = "floors", primitiveType = "int", multiplicity = MultiplicityData.single())
                )
            ),
            ClassData(
                name = "Room",
                isAbstract = false,
                extends = emptyList(),
                properties = listOf(
                    PropertyData(name = "name", primitiveType = "string", multiplicity = MultiplicityData.single()),
                    PropertyData(name = "area", primitiveType = "double", multiplicity = MultiplicityData.single())
                )
            )
        ),
        enums = emptyList(),
        associations = listOf(
            AssociationData(
                source = AssociationEndData(className = "House", name = "rooms", multiplicity = MultiplicityData.many()),
                operator = "<>->",
                target = AssociationEndData(className = "Room", name = "house", multiplicity = MultiplicityData.single())
            )
        )
    )

    private val metamodel = Metamodel.compile(metamodelData)

    private val modelData = ModelData(
        metamodelPath = "test.mm",
        instances = listOf(
            ModelDataInstance(
                name = "house1",
                className = "House",
                properties = mapOf(
                    "address" to ModelDataPropertyValue.StringValue("123 Main St"),
                    "floors" to ModelDataPropertyValue.NumberValue(2.0)
                )
            ),
            ModelDataInstance(
                name = "room1",
                className = "Room",
                properties = mapOf(
                    "name" to ModelDataPropertyValue.StringValue("Kitchen"),
                    "area" to ModelDataPropertyValue.NumberValue(20.0)
                )
            ),
            ModelDataInstance(
                name = "room2",
                className = "Room",
                properties = mapOf(
                    "name" to ModelDataPropertyValue.StringValue("Bedroom"),
                    "area" to ModelDataPropertyValue.NumberValue(15.0)
                )
            )
        ),
        links = listOf(
            ModelDataLink(
                sourceName = "house1",
                sourceProperty = "rooms",
                targetName = "room1",
                targetProperty = "house"
            ),
            ModelDataLink(
                sourceName = "house1",
                sourceProperty = "rooms",
                targetName = "room2",
                targetProperty = "house"
            )
        )
    )

    private lateinit var mdeoGraph: MdeoModelGraph
    private lateinit var tinkerGraph: TinkerModelGraph

    @BeforeEach
    fun setUp() {
        mdeoGraph = MdeoModelGraph.create(modelData, metamodel)
        tinkerGraph = TinkerModelGraph.create(modelData, metamodel)
    }

    @AfterEach
    fun tearDown() {
        mdeoGraph.close()
        tinkerGraph.close()
    }

    /**
     * Helper to get the graph key for a property of a given class. 
     */
    private fun graphKey(className: String, propName: String): String {
        val fieldIndex = metamodel.metadata.classes[className]!!.propertyFields[propName]!!.fieldIndex
        return "prop_$fieldIndex"
    }

    // ========================================================================
    // Basic Graph Operations
    // ========================================================================

    // ------------------------------------------------------------------
    // Model statistics (match-planner input)
    // ------------------------------------------------------------------

    /**
     * Both backends must agree with a from-scratch count of the same graph, since the match
     * planner treats the two implementations as interchangeable sources of cardinalities.
     */
    @Test
    fun statistics_matchAFullRescan_onBothBackends() {
        for (graph in listOf<ModelGraph>(mdeoGraph, tinkerGraph)) {
            val stats = graph.statistics
            assertEquals(3, stats.vertexCount)
            assertEquals(2, stats.edgeCount)
            assertEquals(1, stats.verticesWithLabel("House"))
            assertEquals(2, stats.verticesWithLabel("Room"))
            assertEquals(2, stats.edgesWithLabel(EdgeLabelUtils.computeEdgeLabel("rooms", "house")))
            assertEquals(0, stats.verticesWithLabel("Garage"))
        }
    }

    /**
     * [MdeoGraph] maintains its label counters incrementally rather than rescanning, so every
     * mutation entry point has to keep them exact.
     */
    @Test
    fun statistics_trackVertexAndEdgeMutations() {
        val edgeLabel = EdgeLabelUtils.computeEdgeLabel("rooms", "house")
        val g = mdeoGraph.traversal()

        g.addV("Room").next()
        assertEquals(3, mdeoGraph.statistics.verticesWithLabel("Room"))
        assertEquals(4, mdeoGraph.statistics.vertexCount)

        g.V().hasLabel("Room").has(graphKey("Room", "name"), "Kitchen").drop().iterate()
        assertEquals(2, mdeoGraph.statistics.verticesWithLabel("Room"))
        // Dropping a vertex drops its incident edge too.
        assertEquals(1, mdeoGraph.statistics.edgesWithLabel(edgeLabel))

        g.E().drop().iterate()
        assertEquals(0, mdeoGraph.statistics.edgesWithLabel(edgeLabel))
        assertEquals(0, mdeoGraph.statistics.edgeCount)
    }

    /** A deep copy inherits the counters, and the two graphs then diverge independently. */
    @Test
    fun statistics_areIndependentAfterDeepCopy() {
        val copy = mdeoGraph.deepCopy()
        try {
            assertEquals(2, copy.statistics.verticesWithLabel("Room"))

            copy.traversal().addV("Room").next()

            assertEquals(3, copy.statistics.verticesWithLabel("Room"))
            assertEquals(2, mdeoGraph.statistics.verticesWithLabel("Room"))
        } finally {
            copy.close()
        }
    }

    @Test
    fun createFromModelData_producesCorrectVerticesAndEdges() {
        val g = mdeoGraph.traversal()
        val vertexCount = g.V().count().next()
        val edgeCount = g.E().count().next()

        assertEquals(3L, vertexCount)
        assertEquals(2L, edgeCount)

        // Verify labels
        val labels = g.V().label().toList().sorted()
        assertEquals(listOf("House", "Room", "Room"), labels)
    }

    @Test
    fun createEmpty_hasNoVerticesOrEdges() {
        val empty = MdeoModelGraph.create(
            ModelData(metamodelPath = "test.mm", instances = emptyList(), links = emptyList()),
            metamodel
        )
        val g = empty.traversal()

        assertEquals(0L, g.V().count().next())
        assertEquals(0L, g.E().count().next())
        empty.close()
    }

    @Test
    fun traversal_returnsWorkingGraphTraversalSource() {
        val g = mdeoGraph.traversal()
        assertNotNull(g)

        // Should be able to run a basic traversal
        val houseCount = g.V().hasLabel("House").count().next()
        assertEquals(1L, houseCount)
    }

    @Test
    fun vertexProperties_returnCorrectValues() {
        val g = mdeoGraph.traversal()
        val addressKey = graphKey("House", "address")
        val floorsKey = graphKey("House", "floors")

        val address = g.V().hasLabel("House").values<Any>(addressKey).next()
        assertEquals("123 Main St", address)

        val floors = g.V().hasLabel("House").values<Any>(floorsKey).next()
        assertEquals(2, floors)
    }

    @Test
    fun listProperties_returnAllElements() {
        // Create a metamodel with a list property
        val listMetamodelData = MetamodelData(
            classes = listOf(
                ClassData(
                    name = "Container",
                    isAbstract = false,
                    extends = emptyList(),
                    properties = listOf(
                        PropertyData(name = "tags", primitiveType = "string", multiplicity = MultiplicityData.many())
                    )
                )
            ),
            enums = emptyList(),
            associations = emptyList()
        )
        val listMetamodel = Metamodel.compile(listMetamodelData)
        val listModelData = ModelData(
            metamodelPath = "test.mm",
            instances = listOf(
                ModelDataInstance(
                    name = "c1",
                    className = "Container",
                    properties = mapOf(
                        "tags" to ModelDataPropertyValue.ListValue(
                            listOf(
                                ModelDataPropertyValue.StringValue("alpha"),
                                ModelDataPropertyValue.StringValue("beta"),
                                ModelDataPropertyValue.StringValue("gamma")
                            )
                        )
                    )
                )
            ),
            links = emptyList()
        )
        val listGraph = MdeoModelGraph.create(listModelData, listMetamodel)
        val g = listGraph.traversal()
        val tagsKey = "prop_${listMetamodel.metadata.classes["Container"]!!.propertyFields["tags"]!!.fieldIndex}"

        val tags = g.V().hasLabel("Container").values<String>(tagsKey).toList()
        assertEquals(3, tags.size)
        assertTrue(tags.containsAll(listOf("alpha", "beta", "gamma")))
        listGraph.close()
    }

    @Test
    fun addVertex_createsNewVertex() {
        val g = mdeoGraph.traversal()
        val countBefore = g.V().count().next()

        // Add a new Room vertex via traversal
        val g2 = mdeoGraph.traversal()
        g2.addV("Room").next()
        val countAfter = mdeoGraph.traversal().V().count().next()

        assertEquals(countBefore + 1, countAfter)
    }

    @Test
    fun addEdge_createsNewEdgeAndUpdatesModelAssociations() {
        // Create a fresh graph with no links initially
        val noLinksModelData = ModelData(
            metamodelPath = "test.mm",
            instances = listOf(
                ModelDataInstance(
                    name = "h1",
                    className = "House",
                    properties = mapOf(
                        "address" to ModelDataPropertyValue.StringValue("456 Oak Ave"),
                        "floors" to ModelDataPropertyValue.NumberValue(1.0)
                    )
                ),
                ModelDataInstance(
                    name = "r1",
                    className = "Room",
                    properties = mapOf(
                        "name" to ModelDataPropertyValue.StringValue("Lounge"),
                        "area" to ModelDataPropertyValue.NumberValue(30.0)
                    )
                )
            ),
            links = emptyList()
        )
        val graph = MdeoModelGraph.create(noLinksModelData, metamodel)
        val g = graph.traversal()

        assertEquals(0L, g.E().count().next())

        // Add an edge between house and room
        val house = graph.traversal().V().hasLabel("House").next()
        val room = graph.traversal().V().hasLabel("Room").next()
        val edgeLabel = EdgeLabelUtils.computeEdgeLabel("rooms", "house")
        house.addEdge(edgeLabel, room)

        assertEquals(1L, graph.traversal().E().count().next())

        // Verify the backing model instance associations were updated
        val houseVertex = house as MdeoVertex
        val roomVertex = room as MdeoVertex
        val houseInstance = houseVertex.backingInstance
        val roomInstance = roomVertex.backingInstance

        // The "rooms" field on house should contain the room instance
        @Suppress("UNCHECKED_CAST")
        val rooms = houseInstance.getPropertyByKey("rooms") as? Set<ModelInstance>
        assertNotNull(rooms)
        assertEquals(1, rooms.size)

        // The "house" field on room should reference the house instance
        val houseRef = roomInstance.getPropertyByKey("house") as? ModelInstance
        assertNotNull(houseRef)
        assertSame(houseInstance, houseRef)

        graph.close()
    }

    @Test
    fun removeVertex_removesVertexAndEdges() {
        val g = mdeoGraph.traversal()
        assertEquals(3L, g.V().count().next())
        assertEquals(2L, g.E().count().next())

        // Remove one of the rooms
        val room = mdeoGraph.traversal().V().hasLabel("Room").next()
        room.remove()

        assertEquals(2L, mdeoGraph.traversal().V().count().next())
        // One edge should remain (the other room still linked)
        assertEquals(1L, mdeoGraph.traversal().E().count().next())
    }

    @Test
    fun removeEdge_removesEdge() {
        assertEquals(2L, mdeoGraph.traversal().E().count().next())

        val edge = mdeoGraph.traversal().E().next()
        edge.remove()

        assertEquals(1L, mdeoGraph.traversal().E().count().next())
    }

    // ========================================================================
    // Comparison with TinkerModelGraph
    // ========================================================================

    @Test
    fun matchesTinkerModelGraph_vertexCount() {
        val mdeoCount = mdeoGraph.traversal().V().count().next()
        val tinkerCount = tinkerGraph.traversal().V().count().next()
        assertEquals(tinkerCount, mdeoCount)
    }

    @Test
    fun matchesTinkerModelGraph_edgeCount() {
        val mdeoCount = mdeoGraph.traversal().E().count().next()
        val tinkerCount = tinkerGraph.traversal().E().count().next()
        assertEquals(tinkerCount, mdeoCount)
    }

    @Test
    fun matchesTinkerModelGraph_propertyValues() {
        val addressKey = graphKey("House", "address")
        val floorsKey = graphKey("House", "floors")
        val nameKey = graphKey("Room", "name")
        val areaKey = graphKey("Room", "area")

        // Compare House properties
        val mdeoAddress = mdeoGraph.traversal().V().hasLabel("House").values<Any>(addressKey).next()
        val tinkerAddress = tinkerGraph.traversal().V().hasLabel("House").values<Any>(addressKey).next()
        assertEquals(tinkerAddress, mdeoAddress)

        val mdeoFloors = mdeoGraph.traversal().V().hasLabel("House").values<Any>(floorsKey).next()
        val tinkerFloors = tinkerGraph.traversal().V().hasLabel("House").values<Any>(floorsKey).next()
        // TinkerGraph stores as Double (from NumberValue), MdeoGraph stores as Int (from metamodel type)
        assertEquals((tinkerFloors as Number).toDouble(), (mdeoFloors as Number).toDouble(), 0.001)

        // Compare Room property names (sorted for determinism)
        val mdeoNames = mdeoGraph.traversal().V().hasLabel("Room").values<Any>(nameKey).toList().map { it.toString() }.sorted()
        val tinkerNames = tinkerGraph.traversal().V().hasLabel("Room").values<Any>(nameKey).toList().map { it.toString() }.sorted()
        assertEquals(tinkerNames, mdeoNames)

        // Compare Room areas (sorted for determinism)
        val mdeoAreas = mdeoGraph.traversal().V().hasLabel("Room").values<Any>(areaKey).toList().map { (it as Number).toDouble() }.sorted()
        val tinkerAreas = tinkerGraph.traversal().V().hasLabel("Room").values<Any>(areaKey).toList().map { (it as Number).toDouble() }.sorted()
        assertEquals(tinkerAreas, mdeoAreas)
    }

    @Test
    fun matchesTinkerModelGraph_traversalResults() {
        // Compare counts by label
        val mdeoHouseCount = mdeoGraph.traversal().V().hasLabel("House").count().next()
        val tinkerHouseCount = tinkerGraph.traversal().V().hasLabel("House").count().next()
        assertEquals(tinkerHouseCount, mdeoHouseCount)

        val mdeoRoomCount = mdeoGraph.traversal().V().hasLabel("Room").count().next()
        val tinkerRoomCount = tinkerGraph.traversal().V().hasLabel("Room").count().next()
        assertEquals(tinkerRoomCount, mdeoRoomCount)

        // Both should find same number of edges with the rooms->house label
        val edgeLabel = EdgeLabelUtils.computeEdgeLabel("rooms", "house")
        val mdeoEdgeCount = mdeoGraph.traversal().E().hasLabel(edgeLabel).count().next()
        val tinkerEdgeCount = tinkerGraph.traversal().E().hasLabel(edgeLabel).count().next()
        assertEquals(tinkerEdgeCount, mdeoEdgeCount)
    }

    // ========================================================================
    // Deep Copy
    // ========================================================================

    @Test
    fun deepCopy_createsIndependentGraph() {
        val copy = mdeoGraph.deepCopy()

        // Modify original — add a vertex
        mdeoGraph.traversal().addV("Room").next()

        // Copy should be unaffected
        assertEquals(3L, copy.traversal().V().count().next())
        assertEquals(4L, mdeoGraph.traversal().V().count().next())

        copy.close()
    }

    @Test
    fun deepCopy_preservesAllData() {
        val copy = mdeoGraph.deepCopy()

        assertEquals(
            mdeoGraph.traversal().V().count().next(),
            copy.traversal().V().count().next()
        )
        assertEquals(
            mdeoGraph.traversal().E().count().next(),
            copy.traversal().E().count().next()
        )

        // Verify all labels match
        val origLabels = mdeoGraph.traversal().V().label().toList().sorted()
        val copyLabels = copy.traversal().V().label().toList().sorted()
        assertEquals(origLabels, copyLabels)

        // Verify property values match
        val addressKey = graphKey("House", "address")
        val origAddress = mdeoGraph.traversal().V().hasLabel("House").values<Any>(addressKey).next()
        val copyAddress = copy.traversal().V().hasLabel("House").values<Any>(addressKey).next()
        assertEquals(origAddress, copyAddress)

        copy.close()
    }

    // ========================================================================
    // Reset Nondeterminism
    // ========================================================================

    @Test
    fun resetNondeterminism_returnsEmptyMap() {
        // MdeoModelGraph always returns empty map since IDs don't change
        val idMap = mdeoGraph.resetNondeterminism()
        assertTrue(idMap.isEmpty())
    }

    @Test
    fun resetNondeterminism_shufflesVertexOrder() {
        // Try multiple times to detect shuffling (since shuffling could theoretically
        // produce the same order, we retry a few times)
        var foundDifferentOrder = false
        val originalOrder = mdeoGraph.traversal().V().id().toList()

        for (i in 0 until 20) {
            mdeoGraph.resetNondeterminism()
            val newOrder = mdeoGraph.traversal().V().id().toList()
            if (newOrder != originalOrder) {
                foundDifferentOrder = true
                break
            }
        }

        // With 3 vertices there are 6 permutations, so the probability of all 20
        // attempts returning the same order is (1/6)^20 ≈ 0, effectively impossible
        assertTrue(foundDifferentOrder, "Expected vertex order to change after resetNondeterminism")
    }

    @Test
    fun resetNondeterminism_shufflesLabelFilteredVertexOrder() {
        var foundDifferentOrder = false
        val originalOrder = mdeoGraph.traversal().V().hasLabel("Room").id().toList()
        val expectedIds = originalOrder.sortedBy { it.toString() }

        for (i in 0 until 20) {
            mdeoGraph.resetNondeterminism()
            val newOrder = mdeoGraph.traversal().V().hasLabel("Room").id().toList()
            assertEquals(expectedIds, newOrder.sortedBy { it.toString() })
            if (newOrder != originalOrder) {
                foundDifferentOrder = true
                break
            }
        }

        assertTrue(foundDifferentOrder, "Expected label-filtered vertex order to change after resetNondeterminism")
    }

    @Test
    fun multiLabelScan_keepsLabelOrderUntilShuffled_thenMixesLabels() {
        // A pattern object of a class with subclasses scans several labels at once. Joined in
        // label order, its first candidate would always carry the first label.
        fun firstLabel() = mdeoGraph.traversal().V().hasLabel("House", "Room").label().next()
        val unshuffled = (0 until 10).map { firstLabel() }.toSet()
        assertEquals(1, unshuffled.size, "Expected a stable order before resetNondeterminism")

        val firstLabels = (0 until 60).map {
            mdeoGraph.resetNondeterminism()
            firstLabel()
        }.toSet()
        assertEquals(setOf("House", "Room"), firstLabels)
    }

    // ========================================================================
    // ModelData Round-trip
    // ========================================================================

    @Test
    fun toModelData_andBack_preservesStructure() {
        val exported = mdeoGraph.toModelData()

        // Rebuild from exported data
        val rebuilt = MdeoModelGraph.create(exported, metamodel)

        assertEquals(
            mdeoGraph.traversal().V().count().next(),
            rebuilt.traversal().V().count().next()
        )
        assertEquals(
            mdeoGraph.traversal().E().count().next(),
            rebuilt.traversal().E().count().next()
        )

        // Verify instance names are preserved
        val origNames = mdeoGraph.nameRegistry.getAllMappings().values.sorted()
        val rebuiltNames = rebuilt.nameRegistry.getAllMappings().values.sorted()
        assertEquals(origNames, rebuiltNames)

        // Verify property values survive round-trip
        val addressKey = graphKey("House", "address")
        val origAddress = mdeoGraph.traversal().V().hasLabel("House").values<Any>(addressKey).next()
        val rebuiltAddress = rebuilt.traversal().V().hasLabel("House").values<Any>(addressKey).next()
        assertEquals(origAddress, rebuiltAddress)

        rebuilt.close()
    }

    // ========================================================================
    // VertexRef
    // ========================================================================

    @Test
    fun createVertexRef_tracksVertex() {
        val vertexId = mdeoGraph.traversal().V().next().id()
        val ref = mdeoGraph.createVertexRef(vertexId)

        assertNotNull(ref)
        assertEquals(vertexId, ref.rawId)
    }

    // ========================================================================
    // ASM getPropertyByKey / setPropertyByKey
    // ========================================================================

    @Test
    fun modelInstancePropertyAccess_getByKey() {
        val instance = metamodel.createInstance("House")
        // Set via ASM
        instance.setPropertyByKey("address", "Test Address")
        instance.setPropertyByKey("floors", 3)

        assertEquals("Test Address", instance.getPropertyByKey("address"))
        assertEquals(3, instance.getPropertyByKey("floors"))
    }

    @Test
    fun modelInstancePropertyAccess_setByKey() {
        val instance = metamodel.createInstance("Room")
        instance.setPropertyByKey("name", "Living Room")
        instance.setPropertyByKey("area", 25.0)

        assertEquals("Living Room", instance.getPropertyByKey("name"))
        assertEquals(25.0, instance.getPropertyByKey("area"))

        // Modify
        instance.setPropertyByKey("name", "Dining Room")
        assertEquals("Dining Room", instance.getPropertyByKey("name"))
    }

    // ========================================================================
    // Bidirectional association consistency tests
    // ========================================================================

    /**
     * Reproduces the bug where a "move" transformation (create new link, then delete old link)
     * corrupts the single-valued end of a bidirectional association.
     *
     * The sequence is:
     *   1. Initial state: workItem assigned to sprint2 (edge sprint2→workItem exists)
     *   2. CREATE edge sprint1→workItem  → workItem.isPlannedFor = sprint1  ✓
     *   3. DELETE edge sprint2→workItem  → should leave workItem.isPlannedFor = sprint1,
     *                                      but the bug unconditionally sets isPlannedFor = null
     *
     * After the fix the single-valued [isPlannedFor] field must NOT be nullified by the
     * delete step when the value has already been reassigned by the preceding add-edge step.
     */
    @Test
    fun moveItemBetweenSprints_doesNotCorruptSingleValuedAssociationEnd() {
        // --- metamodel: Sprint.committedItems (0..*) <-> WorkItem.isPlannedFor (0..1) ---
        val sprintMetamodelData = MetamodelData(
            classes = listOf(
                ClassData(
                    name = "Sprint",
                    isAbstract = false,
                    extends = emptyList(),
                    properties = emptyList()
                ),
                ClassData(
                    name = "WorkItem",
                    isAbstract = false,
                    extends = emptyList(),
                    properties = emptyList()
                )
            ),
            enums = emptyList(),
            associations = listOf(
                AssociationData(
                    source = AssociationEndData(
                        className = "Sprint",
                        name = "committedItems",
                        multiplicity = MultiplicityData.many()
                    ),
                    operator = "<->",
                    target = AssociationEndData(
                        className = "WorkItem",
                        name = "isPlannedFor",
                        multiplicity = MultiplicityData.single()
                    )
                )
            )
        )
        val sprintMetamodel = Metamodel.compile(sprintMetamodelData)

        // Initial model: sprint1, sprint2, workItem – workItem already assigned to sprint2
        val edgeLabel = EdgeLabelUtils.computeEdgeLabel("committedItems", "isPlannedFor")
        val modelData = ModelData(
            metamodelPath = "test.mm",
            instances = listOf(
                ModelDataInstance(name = "sprint1", className = "Sprint", properties = emptyMap()),
                ModelDataInstance(name = "sprint2", className = "Sprint", properties = emptyMap()),
                ModelDataInstance(name = "workItem", className = "WorkItem", properties = emptyMap())
            ),
            links = listOf(
                ModelDataLink(
                    sourceName = "sprint2",
                    sourceProperty = "committedItems",
                    targetName = "workItem",
                    targetProperty = "isPlannedFor"
                )
            )
        )
        val graph = MdeoModelGraph.create(modelData, sprintMetamodel)
        val g = graph.traversal()

        val sprint1Id = graph.nameRegistry.getVertexId("sprint1") as Int
        val sprint2Id = graph.nameRegistry.getVertexId("sprint2") as Int
        val sprint1 = g.V(sprint1Id).next() as MdeoVertex
        val sprint2 = g.V(sprint2Id).next() as MdeoVertex
        val workItem = g.V().hasLabel("WorkItem").next() as MdeoVertex

        val sprint1Instance = sprint1.backingInstance
        val sprint2Instance = sprint2.backingInstance
        val workItemInstance = workItem.backingInstance

        // Verify initial state
        assertSame(sprint2Instance, workItemInstance.getPropertyByKey("isPlannedFor") as? ModelInstance,
            "Initial state: workItem.isPlannedFor should be sprint2")
        @Suppress("UNCHECKED_CAST")
        val sprint2Items = sprint2Instance.getPropertyByKey("committedItems") as? Set<ModelInstance>
        assertNotNull(sprint2Items)
        assertTrue(sprint2Items.contains(workItemInstance))

        // Step 1 (CREATE): Add edge sprint1 → workItem  (mimics transformation create-link step)
        sprint1.addEdge(edgeLabel, workItem)

        // After create: workItem.isPlannedFor must be sprint1
        assertSame(
            sprint1Instance,
            workItemInstance.getPropertyByKey("isPlannedFor") as? ModelInstance,
            "After CREATE edge sprint1→workItem, workItem.isPlannedFor should be sprint1"
        )
        @Suppress("UNCHECKED_CAST")
        val sprint1Items = sprint1Instance.getPropertyByKey("committedItems") as? Set<ModelInstance>
        assertNotNull(sprint1Items)
        assertTrue(sprint1Items.contains(workItemInstance))

        // Step 2 (DELETE): Remove edge sprint2 → workItem  (mimics transformation delete-link step)
        val edgeToRemove = g.V(sprint2.id()).outE(edgeLabel).next()
        edgeToRemove.remove()

        // After delete: workItem.isPlannedFor must STILL be sprint1 (not null!)
        assertSame(
            sprint1Instance,
            workItemInstance.getPropertyByKey("isPlannedFor") as? ModelInstance,
            "After DELETE edge sprint2→workItem, workItem.isPlannedFor must remain sprint1, not be nullified"
        )
        // sprint2 must no longer hold workItem
        @Suppress("UNCHECKED_CAST")
        val sprint2ItemsAfter = sprint2Instance.getPropertyByKey("committedItems") as? Set<ModelInstance>
        assertTrue(sprint2ItemsAfter == null || !sprint2ItemsAfter.contains(workItemInstance),
            "sprint2.committedItems must not contain workItem after DELETE")

        graph.close()
    }
}
