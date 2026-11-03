package com.mdeo.optimizer.rulegen

import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataLink
import com.mdeo.optimizer.config.RefinementConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Edge operators are exact: applied to a consistent model, an operator can produce precisely the
 * models that one elementary change of its kind (add, remove, change or swap a link) turns the
 * model into while keeping it consistent — no other model, and every one of them.
 *
 * The expected models are found by brute force over all changes of the kind ([validChanges]).
 * The operator's outcomes are collected by applying it many times, with the engine choosing its
 * match at random as in the optimizer; the number of attempts makes missing a possible outcome by
 * chance practically impossible. The test also checks that an end without an add (or remove) operator really
 * admits no consistent add (or remove).
 *
 * Matching is injective, so changes in which an object would take two roles at once, such as a
 * link from an object to itself, are not counted.
 */
class EdgeOperatorExactnessTest {

    private val bounds = listOf("*", "?", "+", "1", "2..3", "2..*", "1..2", "2")

    @TestFactory
    fun `edge operators for every pair of multiplicities are exact`(): List<DynamicTest> =
        bounds.flatMap { e -> bounds.map { f -> e to f } }.map { (e, f) ->
            DynamicTest.dynamicTest("A.e[$e] <--> B.f[$f]") {
                check(mm("Context", "A", "B") {
                    assoc("Context.as[*]", "*-->", "A.ctx")
                    assoc("Context.bs[*]", "*-->", "B.ctx")
                    assoc("A.e[$e]", "<-->", "B.f[$f]")
                }, ends = listOf("A.e", "B.f"))
            }
        }

    @TestFactory
    fun `edge operators of other shapes are exact`(): List<DynamicTest> = listOf(
        "unidirectional" to mm("A", "B") { assoc("A.e[1..2]", "-->", "B") },
        "unidirectional, fixed" to mm("A", "B") { assoc("A.e[1]", "-->", "B") },
        "self association" to mm("A") { assoc("A.next[?]", "<-->", "A.prev[?]") },
        "self association, fixed" to mm("A") { assoc("A.friends[1]", "<-->", "A.friendOf[0..2]") },
        "inheritance" to MetamodelBuilder("/test.mm").apply {
            cls("abstract Shape"); cls("Circle : Shape"); cls("Square : Shape"); cls("Tag")
            assoc("Shape.tags[0..2]", "<-->", "Tag.shapes[1..*]")
        }.build(),
        "composition" to mm("House", "Room") { assoc("House.rooms[1..3]", "*-->", "Room.house[?]") },
        "composition, fixed part" to mm("House", "Room") { assoc("House.rooms[*]", "*-->", "Room.house") },
        "two compositions" to mm("Box", "Shelf", "Item") {
            assoc("Box.items[*]", "*-->", "Item.box[?]")
            assoc("Shelf.items[0..2]", "*-->", "Item.shelf[?]")
        },
        "two compositions between the same classes" to mm("Pair", "Part") {
            assoc("Pair.left[?]", "*-->", "Part.leftOf[?]")
            assoc("Pair.right[?]", "*-->", "Part.rightOf[?]")
        },
        "recursive composition" to mm("Node") { assoc("Node.children[0..2]", "*-->", "Node.parent[?]") },
        "mutually recursive compositions" to mm("Folder", "Archive") {
            assoc("Folder.archives[*]", "*-->", "Archive.folder[?]")
            assoc("Archive.folders[*]", "*-->", "Folder.archive[?]")
        },
        "refined" to mm("Server", "Component") { assoc("Component.server[?]", "-->", "Server") }
    ).map { (name, metamodel) ->
        DynamicTest.dynamicTest(name) {
            val refinements = if (name == "refined") listOf(refine("Component.server[1]")) else emptyList()
            val ends = MetamodelInfo(metamodel).allEnds.filter { it.name != null }.map { "${it.owner}.${it.name}" }
            check(metamodel, ends, refinements)
        }
    }

    // -------------------------------------------------------------------------

    private fun check(metamodel: MetamodelData, ends: List<String>, refinements: List<RefinementConfig> = emptyList()) {
        val info = MetamodelInfo(metamodel, refinements.map { MultiplicityOverride(it.className, it.fieldName, it.lower, it.upper) })
        val randomModels = RandomModels(info, metamodel, seed = 5)
        val models = (1..MODELS).mapNotNull { randomModels.next(maxPerClass = 3) }
        assertEquals(MODELS, models.size, "could not build $MODELS consistent models")
        val runner = RuleRunner(metamodel)

        for (end in ends) {
            val specs = listOf(spec("mutate $end"))
            val generated = MutationRuleGenerator.generate(metamodel, specs, refinements).mutations
            val operatorSpecs = operatorSpecs(info, specs).mapValues { it.value as EdgeSpec }
            val kinds = operatorSpecs.values.map { it.kind }.toSet()
            val holder = info.endOf(end.substringBefore("."), end.substringAfter("."))!!

            for (model in models) {
                for (kind in listOf(EdgeKind.ADD, EdgeKind.REMOVE) - kinds) {
                    val possible = validChanges(EdgeSpec(end.substringBefore("."), holder, kind), model, info)
                    assertTrue(possible.isEmpty()) { "$end has no $kind operator, but ${model.render()} admits ${possible.take(3)}" }
                }
                for (mutation in generated) {
                    val expected = validChanges(operatorSpecs.getValue(mutation.name), model, info)
                    val first = runner.apply(mutation, model)
                    assertEquals(expected.isNotEmpty(), first is Applied.Yes) {
                        "${mutation.name} on ${model.render()}: ${expected.size} consistent change(s), but it ${if (first is Applied.Yes) "applied" else "did not apply"}"
                    }
                    val samples = (SAMPLES_PER_CHANGE * expected.size).coerceIn(MIN_SAMPLES, MAX_SAMPLES)
                    val outcomes = (0 until samples).mapNotNull { (runner.apply(mutation, model) as? Applied.Yes)?.model?.key() }.toSet()
                    val unexpected = outcomes - expected
                    assertTrue(unexpected.isEmpty()) { "${mutation.name} on ${model.render()} produced ${unexpected.take(2)}" }
                    val missed = expected - outcomes
                    assertTrue(missed.isEmpty()) {
                        "${mutation.name} on ${model.render()} never produced ${missed.size} of ${expected.size} consistent changes, e.g. ${missed.first()}"
                    }
                }
            }
        }
    }

    private companion object {
        const val MODELS = 6
        const val SAMPLES_PER_CHANGE = 25
        const val MIN_SAMPLES = 40
        const val MAX_SAMPLES = 600
    }
}

/**
 * The keys ([key]) of every consistent model one change of [spec]'s kind turns [model] into,
 * by brute force. Objects at the end's side must be of [EdgeSpec.className] or a subclass; no
 * object may take two roles in the change.
 */
fun validChanges(spec: EdgeSpec, model: ModelData, info: MetamodelInfo): Set<String> {
    val end = spec.end
    val holders = model.instances.filter { spec.className in info.supertypesOf(it.className) }.map { it.name }
    val targets = model.instances.filter { end.target in info.supertypesOf(it.className) }.map { it.name }
    val links = model.linkSet
    val own = links.filter { isOf(it, end) }.map { it to ends(it, end) }.filter { (_, p) -> p.first in holders && p.first != p.second }

    fun with(add: List<Link>, remove: List<Link>): ModelData {
        val result = links - remove.toSet() + add
        return model.copy(links = result.map { ModelDataLink(it.source, it.sourceProperty, it.target, it.targetProperty) })
    }
    val candidates: List<ModelData> = when (spec.kind) {
        EdgeKind.ADD -> holders.flatMap { h -> targets.filter { it != h }.map { t -> linkOf(end, h, t) } }
            .filter { it !in links }.map { with(listOf(it), emptyList()) }
        EdgeKind.REMOVE -> own.map { (link, _) -> with(emptyList(), listOf(link)) }
        EdgeKind.CHANGE_TARGET -> own.flatMap { (link, p) ->
            targets.filter { it != p.second && it != p.first }.map { linkOf(end, p.first, it) }.filter { it !in links }
                .map { with(listOf(it), listOf(link)) }
        }
        EdgeKind.CHANGE_SOURCE -> own.flatMap { (link, p) ->
            holders.filter { it != p.first && it != p.second }.map { linkOf(end, it, p.second) }.filter { it !in links }
                .map { with(listOf(it), listOf(link)) }
        }
        EdgeKind.SWAP -> own.flatMap { (l1, p1) ->
            own.filter { (_, p2) -> setOf(p1.first, p1.second, p2.first, p2.second).size == 4 }.mapNotNull { (l2, p2) ->
                val new = listOf(linkOf(end, p1.first, p2.second), linkOf(end, p2.first, p1.second))
                if (new.any { it in links }) null else with(new, listOf(l1, l2))
            }
        }
    }
    return candidates.filter { problemsOf(it, info).isEmpty() }.map { it.key() }.toSet()
}
