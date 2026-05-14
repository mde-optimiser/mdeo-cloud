import { AstReflectionKey, BaseModelIdProvider, sharedImport } from "@mdeo/language-shared";
import type { ModelIdRegistry } from "@mdeo/language-shared";
import type { AstNode, Reference } from "langium";
import { Model, ObjectInstance, Link, LinkEnd, PropertyAssignment } from "../../grammar/modelTypes.js";
import type {
    PartialObjectInstance,
    PartialLink,
    PartialLinkEnd,
    PartialPropertyAssignment
} from "../../grammar/modelPartialTypes.js";
import type { AstReflection } from "@mdeo/language-common";

const { injectable, inject } = sharedImport("inversify");

/**
 * Provides unique IDs for model AST nodes based on semantic information.
 * IDs are constructed to be deterministic and meaningful.
 */
@injectable()
export class ModelModelIdProvider extends BaseModelIdProvider {
    /**
     * Injected AST reflection service for type checking and model introspection.
     */
    @inject(AstReflectionKey)
    protected reflection!: AstReflection;

    /**
     * Gets the name/ID for an AST node.
     *
     * @param node The AST node
     * @returns The generated name/ID or undefined
     */
    getName(node: AstNode, _registry: ModelIdRegistry): string | undefined {
        if (this.reflection.isInstance(node, Model)) {
            return this.getModelName();
        } else if (this.reflection.isInstance(node, ObjectInstance)) {
            return this.getObjectInstanceName(node);
        } else if (this.reflection.isInstance(node, Link)) {
            return this.getLinkName(node);
        } else if (this.reflection.isInstance(node, LinkEnd)) {
            return this.getLinkEndName(node);
        } else if (this.reflection.isInstance(node, PropertyAssignment)) {
            return this.getPropertyAssignmentName(node);
        }
        return undefined;
    }

    /**
     * Generates ID for Model root node.
     *
     * @returns The model graph ID
     */
    private getModelName(): string {
        return "model-graph";
    }

    /**
     * Generates ID for ObjectInstance node based on class and object name.
     * Format: "ClassName_ObjectName"
     *
     * @param node The object instance
     * @returns The generated ID
     */
    private getObjectInstanceName(node: PartialObjectInstance): string {
        const objectName = BaseModelIdProvider.escapeIdPart(node.name ?? "unnamed");
        const className = this.resolveClassName(node.class);
        return `${className}_${objectName}`;
    }

    /**
     * Generates ID for Link based on source and target objects/properties.
     * Format: "sourceObject_sourceProperty--targetObject_targetProperty"
     *
     * @param node The link
     * @returns The generated ID
     */
    private getLinkName(node: PartialLink): string {
        const sourceEnd = this.formatLinkEnd(node.source);
        const targetEnd = this.formatLinkEnd(node.target);
        return `${sourceEnd}--${targetEnd}`;
    }

    /**
     * Formats a link end for ID generation.
     * Format: "objectName" or "objectName_property"
     *
     * @param linkEnd The link end
     * @returns The formatted string, or "unresolved" if the endpoint cannot be resolved
     */
    private formatLinkEnd(linkEnd: PartialLinkEnd | undefined): string {
        if (linkEnd == undefined) {
            return "unresolved";
        }

        const objectRef = linkEnd.object;
        if (objectRef == undefined || objectRef.ref == undefined) {
            return "unresolved";
        }

        const obj = objectRef.ref as PartialObjectInstance;
        const objectName = BaseModelIdProvider.escapeIdPart(obj.name ?? "unnamed");
        const property = BaseModelIdProvider.escapeIdPart(linkEnd.property?.$refText ?? "");

        if (property) {
            return `${objectName}_${property}`;
        }
        return objectName;
    }

    /**
     * Generates ID for LinkEnd node.
     *
     * @param node The link end
     * @returns The generated ID
     */
    private getLinkEndName(node: PartialLinkEnd): string {
        const parent = node.$container;
        if (parent != undefined && this.reflection.isInstance(parent, Link)) {
            const link = parent as PartialLink;
            const linkName = this.getLinkName(link);
            const isSource = link.source === node;
            return `${linkName}_${isSource ? "source" : "target"}`;
        }
        return "linkEnd";
    }

    /**
     * Generates ID for PropertyAssignment based on parent object and property name.
     *
     * @param node The property assignment
     * @returns The generated ID
     */
    private getPropertyAssignmentName(node: PartialPropertyAssignment): string {
        const propName = BaseModelIdProvider.escapeIdPart(node.name?.$refText ?? node.name?.ref?.name ?? "unnamed");
        const parent = node.$container;

        if (parent != undefined && this.reflection.isInstance(parent, ObjectInstance)) {
            const parentObj = parent as PartialObjectInstance;
            const parentId = this.getObjectInstanceName(parentObj);
            return `${parentId}_prop_${propName}`;
        }

        return propName;
    }

    /**
     * Resolves the class name from a class reference.
     *
     * @param classRef The class reference
     * @returns The resolved class name
     */
    private resolveClassName(classRef: Reference<AstNode> | undefined): string {
        if (classRef == undefined || classRef.error != undefined) {
            return "unresolved";
        }

        const resolved = classRef.ref;
        if (resolved != undefined && "name" in resolved) {
            return BaseModelIdProvider.escapeIdPart((resolved as { name?: string }).name ?? "unknown");
        }

        return BaseModelIdProvider.escapeIdPart(classRef.$refText ?? "unknown");
    }
}
