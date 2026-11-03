package com.mdeo.optimizer.rulegen

import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.AssociationEndData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.EnumData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.metamodel.data.ModelDataLink
import com.mdeo.metamodel.data.ModelDataPropertyValue
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.metamodel.data.PropertyData
import com.mdeo.modeltransformation.graph.ModelGraph
import com.mdeo.modeltransformation.graph.mdeo.MdeoModelGraph
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import com.mdeo.optimizer.config.RefinementConfig
import com.mdeo.modeltransformation.runtime.TransformationEngine
import com.mdeo.modeltransformation.runtime.TransformationExecutionResult
import kotlin.random.Random

/**
 * Small metamodels written the way `.mm` files write them.
 *
 * ```
 * mm("A", "B") { assoc("A.e[1..*]", "<-->", "B.f[0..1]") }
 * ```
 * An end is `Class`, `Class.name` (exactly one) or `Class.name[bounds]` with bounds `n`, `n..m`,
 * `n..*`, `*`, `+` or `?`. A class written `abstract X`, or `X : P, Q` extends `P` and `Q`.
 */
class MetamodelBuilder(private val path: String) {
    private val classes = mutableListOf<ClassData>()
    private val associations = mutableListOf<AssociationData>()
    private val enums = mutableListOf<EnumData>()

    fun cls(spec: String, vararg properties: PropertyData) {
        val abstract = spec.startsWith("abstract ")
        val rest = spec.removePrefix("abstract ")
        val name = rest.substringBefore(":").trim()
        val parents = if (":" in rest) rest.substringAfter(":").split(",").map { it.trim() } else emptyList()
        classes += ClassData(name, abstract, parents, properties.toList())
    }

    fun enum(name: String, vararg entries: String) {
        enums += EnumData(name, entries.toList())
    }

    fun assoc(source: String, operator: String, target: String) {
        associations += AssociationData(end(source), operator, end(target))
    }

    private fun end(spec: String): AssociationEndData {
        val className = spec.substringBefore(".").substringBefore("[")
        if ("." !in spec) return AssociationEndData(className, null, MultiplicityData(1, 1))
        val name = spec.substringAfter(".").substringBefore("[")
        val bounds = if ("[" in spec) spec.substringAfter("[").removeSuffix("]") else "1"
        return AssociationEndData(className, name, multiplicity(bounds))
    }

    fun build() = MetamodelData(path = path, classes = classes, enums = enums, associations = associations)

    companion object {
        fun multiplicity(bounds: String): MultiplicityData = when (bounds) {
            "*" -> MultiplicityData(0, -1)
            "+" -> MultiplicityData(1, -1)
            "?" -> MultiplicityData(0, 1)
            else -> if (".." in bounds) {
                val (lo, hi) = bounds.split("..")
                MultiplicityData(lo.toInt(), if (hi == "*") -1 else hi.toInt())
            } else MultiplicityData(bounds.toInt(), bounds.toInt())
        }
    }
}

fun mm(vararg classes: String, path: String = "/test.mm", body: MetamodelBuilder.() -> Unit): MetamodelData =
    MetamodelBuilder(path).apply { classes.forEach { cls(it) }; body() }.build()

/** A primitive attribute, for [MetamodelBuilder.cls]. */
fun attr(name: String, type: String, multiplicity: MultiplicityData = MultiplicityData(0, 1)) =
    PropertyData(name, primitiveType = type, multiplicity = multiplicity)

/** An enum attribute, for [MetamodelBuilder.cls]. */
fun enumAttr(name: String, enumName: String, multiplicity: MultiplicityData = MultiplicityData(0, 1)) =
    PropertyData(name, enumType = enumName, multiplicity = multiplicity)

fun refine(spec: String): RefinementConfig {
    val className = spec.substringBefore(".")
    val name = spec.substringAfter(".").substringBefore("[")
    val m = MetamodelBuilder.multiplicity(spec.substringAfter("[").removeSuffix("]"))
    return RefinementConfig(className, name, m.lower, m.upper)
}

fun spec(text: String): MutationRuleSpec {
    val keyword = text.substringBefore(" ")
    val target = text.substringAfter(" ")
    val action = if (keyword == "mutate") MutationAction.ALL else MutationAction.valueOf(keyword.uppercase())
    return MutationRuleSpec(target.substringBefore("."), target.substringAfter(".", "").ifEmpty { null }, action)
}

/**
 * Generates random models that respect the multiplicities of an [info], by repeated random
 * construction until one is consistent.
 */
class RandomModels(private val info: MetamodelInfo, private val metamodelData: MetamodelData, seed: Int) {
    private val random = Random(seed)

    /**
     * Returns a consistent model with up to [maxPerClass] objects per concrete class, or `null`
     * when none was found in [attempts] tries.
     */
    fun next(maxPerClass: Int = 7, attempts: Int = 5000): ModelData? {
        repeat(attempts) {
            val model = attempt(maxPerClass)
            if (MultiplicityChecker.violations(model, info).isEmpty()) return model
        }
        return null
    }

    private fun attempt(maxPerClass: Int): ModelData {
        val instances = metamodelData.classes.filter { !it.isAbstract }.flatMap { c ->
            (1..random.nextInt(1, maxPerClass + 1)).map { i ->
                ModelDataInstance("${c.name.lowercase()}$i", c.name, attributes(c.name))
            }
        }.toMutableList()
        val links = mutableListOf<ModelDataLink>()
        val count = HashMap<Pair<String, AssociationEnd>, Int>()
        val container = HashMap<String, String>()

        fun objectsOf(className: String) = instances.filter { className in info.supertypesOf(it.className) }.map { it.name }
        fun room(obj: String, end: AssociationEnd) = !end.isBounded || (count[obj to end] ?: 0) < end.upper
        fun linked(a: String, end: AssociationEnd, b: String) = links.any {
            if (end.isSourceSide) it.sourceName == a && it.targetName == b && it.sourceProperty == end.name && it.targetProperty == end.opposite.name
            else it.sourceName == b && it.targetName == a && it.sourceProperty == end.opposite.name && it.targetProperty == end.name
        }
        fun inside(obj: String, whole: String): Boolean {
            var current: String? = whole
            while (current != null) {
                if (current == obj) return true
                current = container[current]
            }
            return false
        }
        fun canLink(a: String, end: AssociationEnd, b: String): Boolean {
            // An object may be its own partner, but not its own whole; such links are kept rare.
            if (a == b && (end.isContainer || end.opposite.isContainer || random.nextInt(5) != 0)) return false
            if (!room(a, end) || !room(b, end.opposite) || linked(a, end, b)) return false
            val part = when {
                end.isContainer -> a
                end.opposite.isContainer -> b
                else -> return true
            }
            val whole = if (part == a) b else a
            return part !in container && !inside(part, whole)
        }
        fun addLink(a: String, end: AssociationEnd, b: String) {
            links += if (end.isSourceSide) ModelDataLink(a, end.name, b, end.opposite.name)
            else ModelDataLink(b, end.opposite.name, a, end.name)
            count.merge(a to end, 1, Int::plus)
            count.merge(b to end.opposite, 1, Int::plus)
            if (end.isContainer) container[a] = b
            if (end.opposite.isContainer) container[b] = a
        }
        fun fill(lowerOnly: Boolean) {
            for (end in info.allEnds.filter { it.isSourceSide }.shuffled(random)) {
                for (side in listOf(end, end.opposite)) {
                    if (side.name == null) continue
                    for (a in objectsOf(side.owner).shuffled(random)) {
                        val want = if (lowerOnly) side.lower
                        else random.nextInt(side.lower, (if (side.isBounded) side.upper else side.lower + 3) + 1)
                        for (b in objectsOf(side.target).shuffled(random)) {
                            if ((count[a to side] ?: 0) >= want) break
                            if (canLink(a, side, b)) addLink(a, side, b)
                        }
                    }
                }
            }
        }

        // Sparse models leave every optional link unset, so that objects without partners or
        // containers, which some operators need, occur too.
        fill(lowerOnly = random.nextInt(4) == 0)
        repeat(3) { fill(lowerOnly = true) }
        // Objects whose lower bounds could not be met are dropped with their links, which may
        // leave partners short in turn; those get another chance before being dropped too.
        repeat(6) {
            val model = ModelData(metamodelData.path, instances.toList(), links.toList())
            val short = MultiplicityChecker.violations(model, info).mapNotNull { v -> v.substringBefore(".").takeIf { " has " in v } }.toSet()
            if (short.isEmpty()) return model
            val drop = short.random(random)
            instances.removeAll { it.name == drop }
            links.filter { it.sourceName == drop || it.targetName == drop }.forEach { l ->
                links.remove(l)
                val sourceEnd = info.allEnds.first { e -> isOf(Link(l.sourceName, l.sourceProperty, l.targetName, l.targetProperty), e) && e.isSourceSide }
                count.merge(l.sourceName to sourceEnd, -1, Int::plus)
                count.merge(l.targetName to sourceEnd.opposite, -1, Int::plus)
                if (container[l.sourceName] == l.targetName) container.remove(l.sourceName)
                if (container[l.targetName] == l.sourceName) container.remove(l.targetName)
            }
            fill(lowerOnly = true)
        }
        return ModelData(metamodelData.path, instances, links)
    }

    private fun attributes(className: String): Map<String, ModelDataPropertyValue> =
        info.propertiesOf(className).filter { it.multiplicity.lower > 0 && it.multiplicity.upper == 1 }.associate { p ->
            p.name to when {
                p.enumType != null -> ModelDataPropertyValue.EnumValue(info.enumEntries(p.enumType!!).first())
                p.primitiveType == "string" -> ModelDataPropertyValue.StringValue("x")
                p.primitiveType == "boolean" -> ModelDataPropertyValue.BooleanValue(true)
                else -> ModelDataPropertyValue.NumberValue(1.0)
            }
        }
}

/** The outcome of applying one operator once. */
sealed interface Applied {
    data class Yes(val model: ModelData) : Applied
    data object No : Applied
}

/** The graph implementations the optimizer can run on. */
enum class Backend {
    MDEO, TINKER;

    fun graph(model: ModelData, metamodel: Metamodel): ModelGraph = when (this) {
        MDEO -> MdeoModelGraph.create(model, metamodel)
        TINKER -> TinkerModelGraph.create(model, metamodel)
    }
}

/**
 * Applies generated operators to models through the real transformation engine. Engine errors
 * are thrown, not reported as an operator that did not apply.
 */
class RuleRunner(metamodelData: MetamodelData, private val backend: Backend = Backend.MDEO) {
    private val metamodel = Metamodel.compile(metamodelData)

    /**
     * Applies [mutation] to [model] once. Without a [seed] the engine picks a random match, as the
     * optimiser does; with one, the objects and links are put in an order drawn from [seed] and the
     * engine takes the first match, so the outcome is reproducible.
     */
    fun apply(mutation: GeneratedMutation, model: ModelData, seed: Int? = null): Applied {
        val input = if (seed == null) model else Random(seed).let { r ->
            model.copy(instances = model.instances.shuffled(r), links = model.links.shuffled(r))
        }
        // The optimizer mutates deep copies of its solutions' graphs; a copy also starts out
        // in shuffled order on some backends.
        val graph = backend.graph(input, metamodel).let { if (seed != null) it else it.deepCopy().also { _ -> it.close() } }
        try {
            val engine = TransformationEngine.create(graph, mutation.typedAst, deterministic = seed != null)
            return when (val result = engine.execute()) {
                is TransformationExecutionResult.Success -> Applied.Yes(graph.toModelData())
                is TransformationExecutionResult.Failure -> Applied.No
                is TransformationExecutionResult.Stopped ->
                    if (result.isNormalStop) Applied.Yes(graph.toModelData()) else Applied.No
            }
        } finally {
            graph.close()
        }
    }
}

/** The operator specs behind the operators [MutationRuleGenerator] generates for [specs], by name. */
fun operatorSpecs(info: MetamodelInfo, specs: List<MutationRuleSpec>): Map<String, OperatorSpec> {
    val generator = SpecsGenerator(info)
    return specs.flatMap { generator.generate(it) }.associateBy { it.name }
}

// -----------------------------------------------------------------------------
// Comparing models
// -----------------------------------------------------------------------------

/** The attributes that have a value; an unset attribute may also be stored as an explicit null. */
private fun Map<String, ModelDataPropertyValue>.set() = filterValues { it !is ModelDataPropertyValue.NullValue }

/** A link as a value, in the direction the model stores it. */
data class Link(val source: String, val sourceProperty: String?, val target: String, val targetProperty: String?) {
    override fun toString() = "$source.${sourceProperty ?: ""}--$target.${targetProperty ?: ""}"
}

val ModelData.linkSet: Set<Link> get() = links.map { Link(it.sourceName, it.sourceProperty, it.targetName, it.targetProperty) }.toSet()

/** The model with names, attributes and links sorted, so that equal models have equal keys. */
fun ModelData.key(): String =
    instances.sortedBy { it.name }.joinToString(" ") { "${it.name}:${it.className}${it.properties.toSortedMap()}" } + " | " +
        linkSet.map { it.toString() }.sorted().joinToString(" ")

/** Whether [link] belongs to the association of [end]. */
fun isOf(link: Link, end: AssociationEnd): Boolean {
    val source = if (end.isSourceSide) end else end.opposite
    return link.sourceProperty == source.name && link.targetProperty == source.opposite.name
}

/** The object of [link] holding [end], and the one it points at. */
fun ends(link: Link, end: AssociationEnd): Pair<String, String> =
    if (end.isSourceSide) link.source to link.target else link.target to link.source

/** The link of [end]'s association from [holder], which holds [end], to [other]. */
fun linkOf(end: AssociationEnd, holder: String, other: String): Link =
    if (end.isSourceSide) Link(holder, end.name, other, end.opposite.name)
    else Link(other, end.opposite.name, holder, end.name)

/** Everything that makes [model] inconsistent: multiplicities, more than one container and duplicate links. */
fun problemsOf(model: ModelData, info: MetamodelInfo): List<String> =
    MultiplicityChecker.violations(model, info) +
        model.links.groupingBy { Link(it.sourceName, it.sourceProperty, it.targetName, it.targetProperty) }.eachCount()
            .filter { it.value > 1 }.map { "the link ${it.key} exists ${it.value} times" }

/**
 * Checks that [after] differs from [before] only the way [spec] may change a model, and returns
 * what does not fit, or `null`. Attributes of objects that exist before and after never change,
 * and no object is linked to itself.
 *
 * - add / remove: one link of the end's association, held by an object of the selected class;
 * - change: one link moved to another partner, at the selected class's side or at the other one;
 * - swap: two links exchange their partners;
 * - create: one new object of the class; links only to it, except partners it takes over from a
 *   donor, which lose their link to the donor;
 * - delete: one object of the class (or a subclass) with all its links; partners that need a
 *   fixed number of links are linked to another object instead, once per link they lost.
 */
fun shapeProblem(spec: OperatorSpec, before: ModelData, after: ModelData, info: MetamodelInfo): String? {
    val beforeObjects = before.instances.associateBy { it.name }
    val afterObjects = after.instances.associateBy { it.name }
    for ((name, obj) in beforeObjects) {
        val now = afterObjects[name] ?: continue
        if (now.className != obj.className) return "$name changed its class"
        if (now.properties.set() != obj.properties.set()) return "$name changed its attributes from ${obj.properties} to ${now.properties}"
    }
    val newObjects = afterObjects.keys - beforeObjects.keys
    val goneObjects = beforeObjects.keys - afterObjects.keys
    val added = after.linkSet - before.linkSet
    val removed = before.linkSet - after.linkSet
    added.firstOrNull { it.source == it.target }?.let { return "linked ${it.source} to itself" }
    fun classOf(name: String) = (afterObjects[name] ?: beforeObjects.getValue(name)).className
    fun isA(name: String, className: String) = className in info.supertypesOf(classOf(name))

    when (spec) {
        is EdgeSpec -> {
            if (newObjects.isNotEmpty() || goneObjects.isNotEmpty()) return "objects were created or deleted"
            val end = spec.end
            val foreign = (added + removed).filterNot { isOf(it, end) }
            if (foreign.isNotEmpty()) return "links of other associations changed: $foreign"
            val a = added.map { ends(it, end) }
            val r = removed.map { ends(it, end) }
            val holders = (a + r).map { it.first }
            if (holders.any { !isA(it, spec.className) }) return "a link is held by an object that is not a ${spec.className}"
            val ok = when (spec.kind) {
                EdgeKind.ADD -> a.size == 1 && r.isEmpty()
                EdgeKind.REMOVE -> a.isEmpty() && r.size == 1
                EdgeKind.CHANGE_TARGET -> a.size == 1 && r.size == 1 && a[0].first == r[0].first && a[0].second != r[0].second
                EdgeKind.CHANGE_SOURCE -> a.size == 1 && r.size == 1 && a[0].second == r[0].second && a[0].first != r[0].first
                EdgeKind.SWAP -> a.size == 2 && r.size == 2 && r[0].first != r[1].first && r[0].second != r[1].second &&
                    a.toSet() == setOf(r[0].first to r[1].second, r[1].first to r[0].second)
            }
            return if (ok) null else "${spec.kind} added $added and removed $removed"
        }
        is CreateSpec -> {
            if (goneObjects.isNotEmpty()) return "objects were deleted: $goneObjects"
            val node = newObjects.singleOrNull() ?: return "created ${newObjects.size} objects"
            if (classOf(node) != spec.className) return "created a ${classOf(node)}, not a ${spec.className}"
            added.firstOrNull { it.source != node && it.target != node }?.let { return "added $it, which does not touch the new object" }
            val stealing = spec.requirements.filter { it.repair != CreateRepair.CONNECT }.map { it.end }
            for (link in removed) {
                val end = stealing.firstOrNull { isOf(link, it) } ?: return "removed $link, which no repair takes over"
                val (donor, partner) = ends(link, end)
                if (donor == node) return "removed $link from the new object"
                if (linkOf(end, node, partner) !in added) return "took $partner from $donor without linking it to the new object"
            }
            return null
        }
        is DeleteSpec -> {
            if (newObjects.isNotEmpty()) return "objects were created: $newObjects"
            val node = goneObjects.singleOrNull() ?: return "deleted ${goneObjects.size} objects"
            if (!isA(node, spec.className)) return "deleted a ${classOf(node)}, not a ${spec.className}"
            removed.firstOrNull { it.source != node && it.target != node }?.let { return "removed $it, which does not touch the deleted object" }
            val incident = before.linkSet.filter { it.source == node || it.target == node }
            if (!removed.containsAll(incident)) return "links to the deleted object remain"
            for (link in added) {
                val end = spec.rehomed.firstOrNull { isOf(link, it) } ?: return "added $link, which no move explains"
                val (partner, home) = ends(link, end)
                if (home == node) return "linked $partner to the deleted object"
                val lost = incident.count { isOf(it, end) && ends(it, end).first == partner }
                val gained = added.count { isOf(it, end) && ends(it, end).first == partner }
                if (lost != gained) return "$partner lost $lost link(s) through ${end.name} but gained $gained"
            }
            return null
        }
    }
}

/**
 * The disjoint union of this model and [other], whose objects are renamed with [suffix]. It is
 * consistent when both models are, and offers several objects in each role.
 */
fun ModelData.union(other: ModelData, suffix: String): ModelData = copy(
    instances = instances + other.instances.map { it.copy(name = it.name + suffix) },
    links = links + other.links.map { it.copy(sourceName = it.sourceName + suffix, targetName = it.targetName + suffix) }
)

/** A readable one-line rendering of a model, for failure messages. */
fun ModelData.render(): String =
    instances.joinToString(" ") { it.name } + " | " +
        links.joinToString(" ") { "${it.sourceName}.${it.sourceProperty ?: ""}--${it.targetName}.${it.targetProperty ?: ""}" }
