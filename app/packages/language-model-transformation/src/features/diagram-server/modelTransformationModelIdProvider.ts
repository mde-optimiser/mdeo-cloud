import { AstReflectionKey, BaseModelIdProvider, sharedImport, type ModelIdRegistry } from "@mdeo/language-shared";
import type { AstNode } from "langium";
import {
    ModelTransformation,
    BaseTransformationStatement,
    MatchStatement,
    IfMatchStatement,
    WhileMatchStatement,
    UntilMatchStatement,
    ForMatchStatement,
    IfExpressionStatement,
    WhileExpressionStatement,
    StopStatement,
    Pattern,
    PatternObjectInstance,
    PatternObjectInstanceReference,
    PatternObjectInstanceDelete,
    PatternLink,
    PatternLinkEnd,
    PatternPropertyAssignment,
    WhereClause,
    PatternVariable,
    ElseIfBranch,
    type PatternObjectInstanceType,
    type PatternObjectInstanceReferenceType,
    type PatternObjectInstanceDeleteType,
    type PatternLinkType,
    type PatternLinkEndType,
    type PatternPropertyAssignmentType,
    type WhereClauseType,
    type PatternVariableType,
    type PatternType
} from "../../grammar/modelTransformationTypes.js";
import type { AstReflection } from "@mdeo/language-common";

const { injectable, inject } = sharedImport("inversify");

/**
 * Provides unique IDs for model transformation AST nodes based on semantic information.
 * IDs are constructed to be deterministic and meaningful.
 */
@injectable()
export class ModelTransformationModelIdProvider extends BaseModelIdProvider {
    @inject(AstReflectionKey)
    protected reflection!: AstReflection;

    getName(node: AstNode, registry: ModelIdRegistry): string | undefined {
        if (this.reflection.isInstance(node, ModelTransformation)) {
            return "transformation-graph";
        }

        if (
            this.reflection.isInstance(node, MatchStatement) ||
            this.reflection.isInstance(node, IfMatchStatement) ||
            this.reflection.isInstance(node, WhileMatchStatement) ||
            this.reflection.isInstance(node, UntilMatchStatement) ||
            this.reflection.isInstance(node, ForMatchStatement) ||
            this.reflection.isInstance(node, IfExpressionStatement) ||
            this.reflection.isInstance(node, WhileExpressionStatement) ||
            this.reflection.isInstance(node, StopStatement)
        ) {
            return this.getHierarchicalStatementName(node, registry);
        }

        if (this.reflection.isInstance(node, Pattern)) {
            return this.getPatternName(node, registry);
        }

        if (this.reflection.isInstance(node, PatternObjectInstance)) {
            return BaseModelIdProvider.escapeIdPart(node.name ?? "unnamed");
        }
        if (this.reflection.isInstance(node, PatternObjectInstanceReference)) {
            return this.getPatternObjectInstanceReferenceName(node, registry);
        }
        if (this.reflection.isInstance(node, PatternObjectInstanceDelete)) {
            return this.getPatternObjectInstanceDeleteName(node, registry);
        }
        if (this.reflection.isInstance(node, PatternLink)) {
            return this.getPatternLinkName(node);
        }
        if (this.reflection.isInstance(node, PatternLinkEnd)) {
            return this.getPatternLinkEndName(node);
        }
        if (this.reflection.isInstance(node, PatternPropertyAssignment)) {
            return this.getPatternPropertyAssignmentName(node);
        }
        if (this.reflection.isInstance(node, WhereClause)) {
            return this.getWhereClauseName(node);
        }
        if (this.reflection.isInstance(node, PatternVariable)) {
            return this.getPatternVariableName(node);
        }

        if (this.reflection.isInstance(node, ElseIfBranch)) {
            return this.getElseIfBranchName(node, registry);
        }

        return undefined;
    }

    /**
     * Traverses the container chain to find the nearest ancestor that is a
     * {@link BaseTransformationStatement} or {@link ModelTransformation}.
     *
     * @param container The AST node to start searching from
     * @returns The nearest parent statement or root transformation, or undefined if none is found
     */
    private findParentStatement(container: AstNode): AstNode | undefined {
        if (this.reflection.isInstance(container, BaseTransformationStatement)) {
            return container;
        }
        if (this.reflection.isInstance(container, ModelTransformation)) {
            return container;
        }
        const parentContainer = (container as AstNode).$container;
        if (parentContainer != null) {
            return this.findParentStatement(parentContainer);
        }
        return undefined;
    }

    /**
     * Generates a hierarchical name for a transformation statement based on its
     * position index within its containing statement or root transformation.
     * Nested statements produce compound names like "1_2" (index 2 inside statement 1).
     *
     * @param node The statement AST node to name
     * @param registry The model ID registry for parent name lookup
     * @returns The hierarchical index string
     */
    private getHierarchicalStatementName(node: AstNode, registry: ModelIdRegistry): string {
        const container = node.$container;
        if (!container || !("$containerProperty" in node)) {
            return "0";
        }
        const prop = node.$containerProperty as string;
        const containerValue = (container as unknown as Record<string, unknown>)[prop];
        let index = 0;
        if (Array.isArray(containerValue)) {
            const idx = containerValue.indexOf(node);
            if (idx >= 0) {
                index = idx;
            }
        }

        const parent = this.findParentStatement(container);
        if (parent == null || this.reflection.isInstance(parent, ModelTransformation)) {
            return String(index);
        }

        const parentName = registry.getName(parent);
        if (parentName !== undefined) {
            return `${parentName}_${index}`;
        }
        return String(index);
    }

    /**
     * Generates a name for a {@link Pattern} node by delegating to the name of
     * its containing transformation statement.
     *
     * @param node The pattern node
     * @param registry The model ID registry for parent statement name lookup
     * @returns The pattern name derived from its containing statement, or "pattern" as fallback
     */
    private getPatternName(node: PatternType, registry: ModelIdRegistry): string {
        const container = node.$container;
        if (container) {
            const parent = this.findParentStatement(container);
            if (parent != null && this.reflection.isInstance(parent, BaseTransformationStatement)) {
                return registry.getName(parent) ?? "pattern";
            }
        }
        return "pattern";
    }

    /**
     * Generates a name for a {@link PatternObjectInstanceReference} by combining
     * the containing statement's name with the referenced instance name.
     *
     * Format: "{statementName}_ref_{instanceName}"
     *
     * @param node The pattern object instance reference node
     * @param registry The model ID registry for parent statement name lookup
     * @returns The compound name identifying this reference within its pattern
     */
    private getPatternObjectInstanceReferenceName(
        node: PatternObjectInstanceReferenceType,
        registry: ModelIdRegistry
    ): string {
        const instanceRefName = BaseModelIdProvider.escapeIdPart(
            node.instance.ref?.name ?? node.instance.$refText ?? "unnamed"
        );
        const container = node.$container;
        if (container) {
            const parent = this.findParentStatement(container);
            if (parent != null && !this.reflection.isInstance(parent, ModelTransformation)) {
                const parentName = registry.getName(parent);
                if (parentName !== undefined) {
                    return `${parentName}_ref_${instanceRefName}`;
                }
            }
        }
        return `ref_${instanceRefName}`;
    }

    /**
     * Generates a name for a {@link PatternObjectInstanceDelete} by combining
     * the containing statement's name with the referenced instance name.
     *
     * Format: "{statementName}_ref_{instanceName}"
     *
     * @param node The pattern object instance delete node
     * @param registry The model ID registry for parent statement name lookup
     * @returns The compound name identifying this delete node within its pattern
     */
    private getPatternObjectInstanceDeleteName(
        node: PatternObjectInstanceDeleteType,
        registry: ModelIdRegistry
    ): string {
        const instanceRefName = BaseModelIdProvider.escapeIdPart(
            node.instance.ref?.name ?? node.instance.$refText ?? "unnamed"
        );
        const container = node.$container;
        if (container) {
            const parent = this.findParentStatement(container);
            if (parent != null && !this.reflection.isInstance(parent, ModelTransformation)) {
                const parentName = registry.getName(parent);
                if (parentName !== undefined) {
                    return `${parentName}_ref_${instanceRefName}`;
                }
            }
        }
        return `ref_${instanceRefName}`;
    }

    /**
     * Generates a name for a {@link PatternLink} from its formatted source and target ends.
     *
     * Format: "{sourceEnd}--{targetEnd}"
     *
     * @param node The pattern link node
     * @returns The generated name combining both link ends
     */
    private getPatternLinkName(node: PatternLinkType): string {
        const sourceEnd = this.formatPatternLinkEnd(node.source);
        const targetEnd = this.formatPatternLinkEnd(node.target);
        return `${sourceEnd}--${targetEnd}`;
    }

    /**
     * Formats a pattern link end for use in a link's name.
     * Resolves the object reference to get the instance name, and appends the
     * optional property with an underscore separator.
     *
     * Format: "{instanceName}" or "{instanceName}_{property}"
     *
     * @param linkEnd The pattern link end to format
     * @returns The formatted string, or "unresolved" if the endpoint cannot be resolved
     */
    private formatPatternLinkEnd(linkEnd: PatternLinkEndType | undefined): string {
        if (linkEnd == undefined) {
            return "unresolved";
        }

        const objectRef = linkEnd.object;
        if (objectRef == undefined || objectRef.ref == undefined) {
            return "unresolved";
        }

        const obj = objectRef.ref as PatternObjectInstanceType;
        const objectName = BaseModelIdProvider.escapeIdPart(obj.name ?? "unnamed");
        const property = BaseModelIdProvider.escapeIdPart(linkEnd.property?.$refText ?? "");

        if (property) {
            return `${objectName}_${property}`;
        }
        return objectName;
    }

    /**
     * Generates a name for a {@link PatternLinkEnd} by appending "_source" or "_target"
     * to its containing link's name.
     *
     * @param node The pattern link end node
     * @returns The generated name for the link end, or "linkEnd" if the container is not a link
     */
    private getPatternLinkEndName(node: PatternLinkEndType): string {
        const parent = node.$container;
        if (parent != undefined && this.reflection.isInstance(parent, PatternLink)) {
            const link = parent as PatternLinkType;
            const linkName = this.getPatternLinkName(link);
            const isSource = link.source === node;
            return `${linkName}_${isSource ? "source" : "target"}`;
        }
        return "linkEnd";
    }

    /**
     * Generates a name for a {@link PatternPropertyAssignment} based on the parent
     * instance name and the assigned property name.
     *
     * Format: "{instanceName}_prop_{propertyName}"
     *
     * @param node The pattern property assignment node
     * @returns The generated name for the property assignment
     */
    private getPatternPropertyAssignmentName(node: PatternPropertyAssignmentType): string {
        const propName = node.name?.$refText ?? node.name?.ref?.name ?? "unnamed";
        const parent = node.$container;

        if (parent != undefined && this.reflection.isInstance(parent, PatternObjectInstance)) {
            const parentObj = parent as PatternObjectInstanceType;
            return `${BaseModelIdProvider.escapeIdPart(parentObj.name ?? "unnamed")}_prop_${BaseModelIdProvider.escapeIdPart(propName)}`;
        }

        return propName;
    }

    /**
     * Generates a name for a {@link WhereClause} based on its index in the containing node.
     *
     * @param node The where clause node
     * @returns The index-based name string, or "where" if the index cannot be determined
     */
    private getWhereClauseName(node: WhereClauseType): string {
        const container = node.$container;
        if (container && "$containerProperty" in node) {
            const prop = node.$containerProperty as string;
            const containerValue = (container as unknown as Record<string, unknown>)[prop];
            if (Array.isArray(containerValue)) {
                const index = containerValue.indexOf(node);
                if (index >= 0) {
                    return String(index);
                }
            }
        }
        return "where";
    }

    /**
     * Generates a name for a {@link PatternVariable} based on its declared name.
     *
     * @param node The pattern variable node
     * @returns The variable's declared name, or "unnamed" if absent
     */
    private getPatternVariableName(node: PatternVariableType): string {
        return BaseModelIdProvider.escapeIdPart(node.name ?? "unnamed");
    }

    /**
     * Generates a name for an {@link ElseIfBranch} based on its position index within
     * its containing {@link IfExpressionStatement} and the name of the parent statement.
     *
     * Format: "{parentName}_elseif_{index}"
     *
     * @param node The else-if branch node
     * @param registry The model ID registry for parent name lookup
     * @returns The compound name identifying this else-if branch
     */
    private getElseIfBranchName(node: AstNode, registry: ModelIdRegistry): string {
        const container = node.$container;
        if (container && "$containerProperty" in node) {
            const prop = node.$containerProperty as string;
            const containerValue = (container as unknown as Record<string, unknown>)[prop];
            let index = 0;
            if (Array.isArray(containerValue)) {
                const idx = containerValue.indexOf(node);
                if (idx >= 0) index = idx;
            }
            const parent = this.findParentStatement(container);
            if (parent != null && !this.reflection.isInstance(parent, ModelTransformation)) {
                const parentName = registry.getName(parent);
                if (parentName !== undefined) return `${parentName}_elseif_${index}`;
            }
            return `elseif_${index}`;
        }
        return "elseif";
    }
}
