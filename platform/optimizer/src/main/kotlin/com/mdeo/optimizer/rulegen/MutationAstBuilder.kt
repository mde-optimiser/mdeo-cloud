package com.mdeo.optimizer.rulegen

import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.statements.TypedForMatchStatement
import com.mdeo.modeltransformation.ast.statements.TypedIfMatchStatement
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.ast.statements.TypedStopStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement

/**
 * Turns an [OperatorSpec] into the transformation that performs it.
 *
 * Every operator keeps a model whose links respect the metamodel's (refined) multiplicities
 * consistent. Upper bounds are checked with `size() < upper` before a link is added, lower bounds
 * with `size() > lower` before one is removed, and a link that already exists is never added
 * twice. An object added to a whole must not be contained by another one yet.
 */
object MutationAstBuilder {

    private const val TOP = 1
    private const val TOP_BLOCK = 2
    private const val FOR_PATTERN = 2
    private const val IF_PATTERN = 4

    /**
     * Builds the transformation for [spec].
     *
     * @param warnings Receives notes on what the operator cannot do, e.g. attributes it leaves unset.
     */
    fun build(spec: OperatorSpec, info: MetamodelInfo, warnings: MutableList<String> = mutableListOf()): TypedAst {
        val w = RuleAstWriter(info)
        val statements = when (spec) {
            is CreateSpec -> listOf(match(create(spec, info, w, warnings)))
            is DeleteSpec -> delete(spec, info, w)
            is EdgeSpec -> listOf(match(edge(spec, info, w)))
        }
        return TypedAst(types = w.types(), metamodelPath = info.metamodelPath, statements = statements)
    }

    private fun match(elements: List<TypedPatternElement>) = TypedMatchStatement(pattern = TypedPattern(elements = elements))

    /**
     * `where a != b` for every two objects [pattern] matches whose classes differ but share
     * objects through inheritance. The engine keeps apart only objects declared with the same
     * class, so an object could otherwise play both roles, e.g. a folder be its own item.
     */
    private fun distinctRelatives(pattern: List<TypedPatternElement>, info: MetamodelInfo, w: RuleAstWriter): List<TypedPatternElement> {
        val matched = pattern.filterIsInstance<TypedPatternObjectInstanceElement>()
            .map { it.objectInstance }
            .filter { it.className != null && it.modifier != "create" }
        return matched.flatMapIndexed { i, a ->
            matched.drop(i + 1)
                .filter { b -> a.className != b.className && info.compatible(a.className!!, b.className!!) }
                .map { b -> w.where(w.distinct(a.name, a.className!!, TOP, b.name, b.className!!, TOP)) }
        }
    }

    /** An object about to be linked to a whole through the composition [association]. */
    private data class NewPart(val name: String, val className: String, val association: Int)

    /**
     * Blocks rejecting the match when one of [parts] already has a container through a composition
     * other than the one it is linked through, so that it stays in at most one.
     *
     * Matching is injective, so an object a block declares never stands for an object [pattern]
     * binds: a container that is itself bound by the pattern, such as the very whole the part is
     * linked to, gets a block of its own naming it.
     */
    private fun otherContainers(
        parts: List<NewPart>,
        pattern: List<TypedPatternElement>,
        info: MetamodelInfo,
        w: RuleAstWriter
    ): List<TypedPatternElement> {
        val bound = pattern.filterIsInstance<TypedPatternObjectInstanceElement>()
            .map { it.objectInstance }
            .filter { it.className != null && it.modifier != "create" }
            .map { it.name to it.className!! }
        return parts.flatMap { part ->
            info.containerEndsCompatibleWith(part.className).filter { it.association != part.association }.flatMapIndexed { i, containerEnd ->
                val whole = "${part.name}_container_$i"
                listOf(w.forbid("${part.name}_uncontained_$i", listOf(w.obj(whole, containerEnd.target), w.link(containerEnd, part.name, whole)))) +
                    bound.filter { (name, className) -> name != part.name && info.compatible(className, containerEnd.target) }
                        .map { (name, _) -> w.forbid("${part.name}_notIn_${name}_$i", listOf(w.link(containerEnd, part.name, name))) }
            }
        }
    }

    // =========================================================================
    // Edges
    // =========================================================================

    private fun edge(spec: EdgeSpec, info: MetamodelInfo, w: RuleAstWriter): List<TypedPatternElement> {
        val a = spec.className
        val e = spec.end
        val o = e.opposite
        val b = e.target
        val out = mutableListOf<TypedPatternElement>()
        val parts = mutableListOf<NewPart>()
        when (spec.kind) {
            EdgeKind.ADD -> {
                out += w.obj("source", a)
                out += w.obj("target", b)
                out += w.forbid("alreadyLinked", listOf(w.link(e, "source", "target")))
                out += w.link(e, "source", "target", "create")
                if (e.isBounded) out += w.where(w.size("source", a, TOP, e, "<", e.upper))
                if (o.name != null && o.isBounded) out += w.where(w.size("target", b, TOP, o, "<", o.upper))
                if (e.isContainment) parts += NewPart("target", b, e.association)
                if (e.isContainer) parts += NewPart("source", a, e.association)
            }
            EdgeKind.REMOVE -> {
                out += w.obj("source", a)
                out += w.obj("target", b)
                out += w.link(e, "source", "target", "delete")
                if (e.lower > 0) out += w.where(w.size("source", a, TOP, e, ">", e.lower))
                if (o.name != null && o.lower > 0) out += w.where(w.size("target", b, TOP, o, ">", o.lower))
            }
            EdgeKind.CHANGE_SOURCE -> {
                out += w.obj("moved", b)
                out += w.obj("oldSource", a)
                out += w.obj("newSource", a)
                out += w.link(e, "oldSource", "moved", "delete")
                out += w.forbid("alreadyLinked", listOf(w.link(e, "newSource", "moved")))
                out += w.link(e, "newSource", "moved", "create")
                if (e.lower > 0) out += w.where(w.size("oldSource", a, TOP, e, ">", e.lower))
                if (e.isBounded) out += w.where(w.size("newSource", a, TOP, e, "<", e.upper))
                if (e.isContainer) parts += NewPart("newSource", a, e.association)
            }
            EdgeKind.CHANGE_TARGET -> {
                out += w.obj("source", a)
                out += w.obj("oldTarget", b)
                out += w.obj("newTarget", b)
                out += w.link(e, "source", "oldTarget", "delete")
                out += w.forbid("alreadyLinked", listOf(w.link(e, "source", "newTarget")))
                out += w.link(e, "source", "newTarget", "create")
                if (o.name != null && o.lower > 0) out += w.where(w.size("oldTarget", b, TOP, o, ">", o.lower))
                if (o.name != null && o.isBounded) out += w.where(w.size("newTarget", b, TOP, o, "<", o.upper))
                if (e.isContainment) parts += NewPart("newTarget", b, e.association)
            }
            EdgeKind.SWAP -> {
                out += w.obj("source", a)
                out += w.obj("otherSource", a)
                out += w.obj("target", b)
                out += w.obj("otherTarget", b)
                out += w.link(e, "source", "target", "delete")
                out += w.link(e, "otherSource", "otherTarget", "delete")
                out += w.forbid("alreadyLinked", listOf(w.link(e, "source", "otherTarget")))
                out += w.forbid("otherAlreadyLinked", listOf(w.link(e, "otherSource", "target")))
                out += w.link(e, "source", "otherTarget", "create")
                out += w.link(e, "otherSource", "target", "create")
            }
        }
        return out + otherContainers(parts, out, info, w) + distinctRelatives(out, info, w)
    }

    // =========================================================================
    // Create
    // =========================================================================

    private fun create(spec: CreateSpec, info: MetamodelInfo, w: RuleAstWriter, warnings: MutableList<String>): List<TypedPatternElement> {
        val out = mutableListOf<TypedPatternElement>()
        val parts = mutableListOf<NewPart>()
        val skipped = mutableListOf<com.mdeo.metamodel.data.PropertyData>()
        out += w.obj("newNode", spec.className, "create", w.defaultAttributes(spec.className, skipped))
        if (skipped.isNotEmpty()) {
            warnings += "'${spec.name}' leaves the required attributes ${skipped.joinToString { "'${it.name}'" }} " +
                "of '${spec.className}' without a value."
        }

        val ctx = spec.context
        if (ctx != null) {
            val containment = ctx.containmentEnd
            out += w.obj("container", ctx.wholeClass)
            out += w.link(containment, "container", "newNode", "create")
            if (containment.name != null && containment.isBounded) {
                out += w.where(w.size("container", ctx.wholeClass, TOP, containment, "<", containment.upper))
            }
        }

        for (requirement in spec.requirements) {
            val e = requirement.end
            val o = e.opposite
            val n = e.lower
            val targets = (1..n).map { "${e.name}_$it" }
            when (requirement.repair) {
                CreateRepair.CONNECT -> for (t in targets) {
                    out += w.obj(t, e.target)
                    out += w.link(e, "newNode", t, "create")
                    if (o.name != null && o.isBounded) out += w.where(w.size(t, e.target, TOP, o, "<", o.upper))
                    if (e.isContainment) parts += NewPart(t, e.target, e.association)
                }
                CreateRepair.LB_REPAIR -> {
                    val donor = "${e.name}_donor"
                    out += w.obj(donor, e.owner)
                    out += w.where(w.size(donor, e.owner, TOP, e, ">", 2 * n - 1))
                    for (t in targets) {
                        out += w.obj(t, e.target)
                        out += w.link(e, donor, t, "delete")
                        out += w.link(e, "newNode", t, "create")
                    }
                }
                CreateRepair.LB_REPAIR_MULTI -> targets.forEachIndexed { i, t ->
                    val donor = "${e.name}_donor_${i + 1}"
                    out += w.obj(donor, e.owner)
                    out += w.where(w.size(donor, e.owner, TOP, e, ">", n))
                    out += w.obj(t, e.target)
                    out += w.link(e, donor, t, "delete")
                    out += w.link(e, "newNode", t, "create")
                }
            }
        }
        return out + otherContainers(parts, out, info, w) + distinctRelatives(out, info, w)
    }

    // =========================================================================
    // Delete
    // =========================================================================

    private fun delete(spec: DeleteSpec, info: MetamodelInfo, w: RuleAstWriter): List<TypedTransformationStatement> {
        val c = spec.className
        val guards = spec.guarded.mapIndexed { i, end ->
            val neighbour = "neighbour_$i"
            w.forbid(
                "${end.name}_keepsLowerBound_$i",
                listOf(
                    w.obj(neighbour, end.owner),
                    w.link(end, neighbour, "node"),
                    w.where(w.size(neighbour, end.owner, TOP_BLOCK, end, "<=", end.lower))
                )
            )
        }
        if (spec.rehomed.isEmpty() && guards.isEmpty()) {
            return listOf(match(listOf(w.obj("node", c, "delete"))))
        }

        val statements = mutableListOf<TypedTransformationStatement>()
        statements += match(listOf(w.obj("node", c)) + guards)
        spec.rehomed.forEachIndexed { i, end ->
            val nodeSide = end.opposite
            val neighbour = "moved_$i"
            val other = "newPartner_$i"
            val place = mutableListOf<TypedPatternElement>(
                w.reference(neighbour),
                w.obj(other, end.target),
                w.where(w.distinct(other, end.target, IF_PATTERN, "node", c, TOP)),
                w.forbid("alreadyLinked_$i", listOf(w.link(end, neighbour, other))),
                w.link(end, neighbour, other, "create")
            )
            // The moved object was bound by the enclosing statement, so matching alone does not
            // keep it from becoming its own new partner.
            if (info.compatible(end.target, end.owner)) {
                place += w.where(w.distinct(other, end.target, IF_PATTERN, neighbour, end.owner, FOR_PATTERN))
            }
            if (nodeSide.name != null && nodeSide.isBounded) {
                place += w.where(w.size(other, end.target, IF_PATTERN, nodeSide, "<", nodeSide.upper))
            }
            if (end.isContainment) {
                place += otherContainers(listOf(NewPart(other, end.target, end.association)), place + w.obj(neighbour, end.owner), info, w)
            }
            statements += TypedForMatchStatement(
                pattern = TypedPattern(elements = listOf(w.reference("node"), w.obj(neighbour, end.owner), w.link(end, neighbour, "node", "delete"))),
                doBlock = listOf(
                    TypedIfMatchStatement(
                        pattern = TypedPattern(elements = place),
                        thenBlock = emptyList(),
                        elseBlock = listOf(TypedStopStatement(keyword = "kill"))
                    )
                )
            )
        }
        statements += match(listOf(w.deleteBound("node")))
        return statements
    }
}
