import { GNode, GNodeBuilder } from "@mdeo/language-shared";
import type { PatternModifierKind } from "@mdeo/protocol-model-transformation";
import { ModelTransformationElementType } from "@mdeo/protocol-model-transformation";

/**
 * Node representing a pattern object instance.
 * Contains the instance's name, optional type, and optional modifier.
 */
export class GPatternInstanceNode extends GNode {
    /**
     * The name of the pattern instance
     */
    name!: string;

    /**
     * The optional type name (class name) of the pattern instance
     */
    typeName?: string;

    /**
     * The modifier kind (none, create, delete, forbid, require)
     */
    modifier!: PatternModifierKind;

    /**
     * The class hierarchy of this instance (class name and all superclass names).
     * Used by the client to validate canConnect against association source/target classes.
     */
    classHierarchy?: string[];

    /**
     * The input model the instance belongs to (the model value a scope block names, e.g.
     * `donor`), or undefined for an instance of the transformed model.
     * Used by the client to refuse links between models.
     */
    model?: string;

    /**
     * Creates a builder for GPatternInstanceNode instances.
     *
     * @returns A new GPatternInstanceNodeBuilder
     */
    static builder(): GPatternInstanceNodeBuilder {
        return new GPatternInstanceNodeBuilder(GPatternInstanceNode).type(
            ModelTransformationElementType.NODE_PATTERN_INSTANCE
        );
    }
}

/**
 * Builder for GPatternInstanceNode instances.
 * Provides fluent API for constructing pattern instance nodes.
 */
export class GPatternInstanceNodeBuilder<
    T extends GPatternInstanceNode = GPatternInstanceNode
> extends GNodeBuilder<T> {
    /**
     * Sets the name of the pattern instance.
     *
     * @param name The instance name
     * @returns This builder for chaining
     */
    name(name: string): this {
        this.proxy.name = name;
        return this;
    }

    /**
     * Sets the type name (class name) of the pattern instance.
     *
     * @param typeName The type/class name
     * @returns This builder for chaining
     */
    typeName(typeName: string): this {
        this.proxy.typeName = typeName;
        return this;
    }

    /**
     * Sets the modifier kind.
     *
     * @param modifier The modifier kind
     * @returns This builder for chaining
     */
    modifier(modifier: PatternModifierKind): this {
        this.proxy.modifier = modifier;
        return this;
    }

    /**
     * Sets the class hierarchy of the pattern instance.
     *
     * @param hierarchy Array of class names from the instance class up through its superclasses
     * @returns This builder for chaining
     */
    classHierarchy(hierarchy: string[]): this {
        this.proxy.classHierarchy = hierarchy;
        return this;
    }

    /**
     * Sets the input model the pattern instance belongs to.
     *
     * @param model The name of the model value, or undefined for the transformed model
     * @returns This builder for chaining
     */
    model(model: string | undefined): this {
        if (model != undefined) {
            this.proxy.model = model;
        }
        return this;
    }
}
