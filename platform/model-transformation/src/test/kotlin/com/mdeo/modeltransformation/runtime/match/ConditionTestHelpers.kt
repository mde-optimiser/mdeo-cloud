package com.mdeo.modeltransformation.runtime.match

import com.mdeo.modeltransformation.ast.patterns.TypedPatternApplicationCondition
import com.mdeo.modeltransformation.ast.patterns.TypedPatternApplicationConditionElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLink
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkEnd
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternPropertyAssignment

/**
 * Test-only builders for application condition blocks and their contents.
 *
 * They mirror the concrete syntax closely: `forbidBlock(node("b", "Node"), link("a", "b"))`
 * corresponds to `forbid { b: Node {} \n a -- b }`.
 */

/**
 * Builds a negative application condition (`forbid`) holding [elements].
 *
 * @param elements The elements of the condition graph.
 * @param name Optional block name.
 * @return The application-condition element.
 */
fun forbidBlock(vararg elements: TypedPatternElement, name: String? = null) =
    TypedPatternApplicationConditionElement(
        condition = TypedPatternApplicationCondition(negative = true, name = name, elements = elements.toList())
    )

/**
 * Builds a positive application condition (`require`) holding [elements].
 *
 * @param elements The elements of the condition graph.
 * @param name Optional block name.
 * @return The application-condition element.
 */
fun requireBlock(vararg elements: TypedPatternElement, name: String? = null) =
    TypedPatternApplicationConditionElement(
        condition = TypedPatternApplicationCondition(negative = false, name = name, elements = elements.toList())
    )

/**
 * Builds an object instance element without a modifier.
 *
 * @param name The instance name.
 * @param className The metamodel class of the instance.
 * @param properties Property constraints on the instance.
 * @return The object instance element.
 */
fun conditionNode(
    name: String,
    className: String,
    properties: List<TypedPatternPropertyAssignment> = emptyList()
) = TypedPatternObjectInstanceElement(
    objectInstance = TypedPatternObjectInstance(
        modifier = null,
        name = name,
        className = className,
        properties = properties
    )
)

/**
 * Builds a reference to an instance of the enclosing pattern, optionally carrying property
 * constraints that the condition checks on that already-bound node.
 *
 * @param name The name of the referenced instance.
 * @param properties Property constraints checked on the referenced node.
 * @return The reference element (an object instance without a class name).
 */
fun conditionReference(
    name: String,
    properties: List<TypedPatternPropertyAssignment> = emptyList()
) = TypedPatternObjectInstanceElement(
    objectInstance = TypedPatternObjectInstance(
        modifier = null,
        name = name,
        className = null,
        properties = properties
    )
)

/**
 * Builds a link element without a modifier.
 *
 * @param sourceName The source instance name.
 * @param sourceProperty The association end at the source, or `null`.
 * @param targetName The target instance name.
 * @param targetProperty The association end at the target, or `null`.
 * @return The link element.
 */
fun conditionLink(
    sourceName: String,
    sourceProperty: String?,
    targetName: String,
    targetProperty: String?
) = TypedPatternLinkElement(
    link = TypedPatternLink(
        modifier = null,
        source = TypedPatternLinkEnd(objectName = sourceName, propertyName = sourceProperty),
        target = TypedPatternLinkEnd(objectName = targetName, propertyName = targetProperty)
    )
)
