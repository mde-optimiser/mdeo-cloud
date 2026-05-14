import { AstReflectionKey, BaseModelIdProvider, LanguageServicesKey, sharedImport } from "@mdeo/language-shared";
import type { ModelIdRegistry } from "@mdeo/language-shared";
import type { AstNode, Reference } from "langium";
import {
    Class,
    Property,
    Association,
    AssociationEnd,
    MetaModel,
    ClassExtension,
    SingleMultiplicity,
    RangeMultiplicity,
    Enum,
    EnumEntry,
    type SingleMultiplicityType,
    type RangeMultiplicityType
} from "../../grammar/metamodelTypes.js";
import type {
    PartialClass,
    PartialProperty,
    PartialAssociation,
    PartialAssociationEnd,
    PartialMetaModel,
    PartialClassExtension,
    PartialEnum,
    PartialEnumEntry
} from "../../grammar/metamodelPartialTypes.js";
import type { AstReflection, LanguageServices } from "@mdeo/language-common";
import { collectImportedMetamodels } from "../importHelpers.js";

const { injectable, inject } = sharedImport("inversify");

/**
 * Provides unique IDs for metamodel AST nodes based on semantic information.
 * IDs are constructed to be deterministic and meaningful.
 */
@injectable()
export class MetamodelModelIdProvider extends BaseModelIdProvider {
    /**
     * Injected AST reflection service for type checking and model introspection.
     */
    @inject(AstReflectionKey)
    protected reflection!: AstReflection;

    /**
     * Injected metamodel language services.
     */
    @inject(LanguageServicesKey)
    protected languageServices!: LanguageServices;

    /**
     * Gets the name/ID for an AST node.
     *
     * @param node The AST node to get the name for
     * @returns The unique name/ID for the node, or undefined
     */
    getName(node: AstNode, registry: ModelIdRegistry): string | undefined {
        if (this.reflection.isInstance(node, MetaModel)) {
            return this.getMetaModelName(node);
        } else if (this.reflection.isInstance(node, Class)) {
            return this.getClassName(node);
        } else if (this.reflection.isInstance(node, Enum)) {
            return this.getEnumName(node);
        } else if (this.reflection.isInstance(node, EnumEntry)) {
            return this.getEnumEntryName(node);
        } else if (this.reflection.isInstance(node, Property)) {
            return this.getPropertyName(node);
        } else if (this.reflection.isInstance(node, Association)) {
            return this.getAssociationName(node);
        } else if (this.reflection.isInstance(node, AssociationEnd)) {
            return this.getAssociationEndName(node);
        } else if (this.reflection.isInstance(node, ClassExtension)) {
            return this.getClassExtensionName(node);
        } else if (
            this.reflection.isInstance(node, SingleMultiplicity) ||
            this.reflection.isInstance(node, RangeMultiplicity)
        ) {
            return this.getMultiplicityName(node, registry);
        }
        return undefined;
    }

    override getAdditional(node: AstNode): AstNode[] {
        return collectImportedMetamodels(node.$document!, this.languageServices.shared.workspace.LangiumDocuments);
    }

    /**
     * Generates ID for MetaModel root node.
     *
     * @param _node The metamodel node
     * @returns The fixed ID for the metamodel graph
     */
    private getMetaModelName(_node: PartialMetaModel): string {
        return "metamodel-graph";
    }

    /**
     * Generates ID for Class node based on class name.
     *
     * @param node The class node
     * @returns The class name or "unnamed"
     */
    private getClassName(node: PartialClass): string {
        return BaseModelIdProvider.escapeIdPart(node.name ?? "unnamed");
    }

    /**
     * Generates ID for Enum node based on enum name.
     */
    private getEnumName(node: PartialEnum): string {
        return BaseModelIdProvider.escapeIdPart(node.name ?? "unnamed");
    }

    /**
     * Generates ID for EnumEntry node based on parent enum and entry name.
     */
    private getEnumEntryName(node: PartialEnumEntry): string {
        const entryName = BaseModelIdProvider.escapeIdPart(node.name ?? "unnamed");
        const parent = node.$container;
        if (parent != undefined && this.reflection.isInstance(parent, Enum)) {
            const parentEnum = parent as PartialEnum;
            return `${BaseModelIdProvider.escapeIdPart(parentEnum.name ?? "unnamed")}_entry_${entryName}`;
        }
        return entryName;
    }

    /**
     * Generates ID for Property node based on parent class and property name.
     */
    private getPropertyName(node: PartialProperty): string {
        const propName = BaseModelIdProvider.escapeIdPart(node.name ?? "unnamed");
        const parent = node.$container;
        if (parent != undefined && this.reflection.isInstance(parent, Class)) {
            const parentClass = parent as PartialClass;
            return `${this.getClassName(parentClass)}_prop_${propName}`;
        }
        return propName;
    }

    /**
     * Generates ID for Association based on start and end classes.
     * Uses class names and properties to create a semantic ID.
     */
    private getAssociationName(node: PartialAssociation): string {
        const startClassName = BaseModelIdProvider.escapeIdPart(this.resolveClassName(node.source?.class));
        const targetClassName = BaseModelIdProvider.escapeIdPart(this.resolveClassName(node.target?.class));
        const startProperty = BaseModelIdProvider.escapeIdPart(node.source?.name ?? "");
        const targetProperty = BaseModelIdProvider.escapeIdPart(node.target?.name ?? "");

        return `${startClassName}_${startProperty}_${targetClassName}_${targetProperty}`;
    }

    /**
     * Generates ID for AssociationEnd.
     */
    private getAssociationEndName(node: PartialAssociationEnd): string {
        const className = BaseModelIdProvider.escapeIdPart(this.resolveClassName(node.class));
        const property = BaseModelIdProvider.escapeIdPart(node.name ?? "noProperty");
        return `${className}_${property}`;
    }

    /**
     * Generates ID for ClassExtension based on parent class and extension name.
     *
     * @param node The class extension node
     * @returns The formatted class extension ID
     */
    private getClassExtensionName(node: PartialClassExtension): string {
        const owningClass = node.$container?.$container as PartialClass | undefined;
        const parentClassName =
            owningClass != undefined
                ? BaseModelIdProvider.escapeIdPart(this.getClassName(owningClass))
                : "unknownParent";
        const extensionName = BaseModelIdProvider.escapeIdPart(this.resolveClassName(node.class));
        return `${parentClassName}_${extensionName}`;
    }

    /**
     * Resolves the class name from a class reference.
     *
     * @param clsReference The reference to a class
     * @returns The resolved class name or a placeholder
     */
    private resolveClassName(clsReference: Reference<AstNode> | undefined): string {
        if (clsReference === undefined || clsReference.error != undefined) {
            return "unresolved";
        }
        const resolved = clsReference.ref;
        if (this.reflection.isInstance(resolved, Class)) {
            return BaseModelIdProvider.escapeIdPart(this.getClassName(resolved));
        }
        if (clsReference.$refText) {
            return BaseModelIdProvider.escapeIdPart(clsReference.$refText);
        }
        return "unknown";
    }

    /**
     * Generates ID for Multiplicity nodes.
     *
     * @param node The multiplicity node
     * @returns The formatted multiplicity ID
     */
    private getMultiplicityName(
        node: SingleMultiplicityType | RangeMultiplicityType,
        registry: ModelIdRegistry
    ): string {
        const owning = node.$container;
        if (owning == undefined) {
            return "unknownParent_multiplicity";
        } else {
            return `${this.getName(owning, registry) ?? "unknownParent"}_multiplicity`;
        }
    }
}
