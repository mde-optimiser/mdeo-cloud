import { type AstReflection, type ExtendedLangiumServices } from "@mdeo/language-common";
import { sharedImport, resolveRelativePath } from "@mdeo/language-shared";
import type { AstNodeDescriptionProvider, LangiumDocuments, ReferenceInfo, Scope } from "langium";
import {
    ObjectInstance,
    PropertyAssignment,
    EnumValue,
    LinkEnd,
    type ModelType,
    type ObjectInstanceType,
    type PropertyAssignmentType,
    type LinkEndType,
    type EnumValueType,
    Model
} from "../grammar/modelTypes.js";
import {
    EnumTypeReference,
    getScopeFromMetamodelFile,
    getExportedEntitiesFromMetamodelFile,
    createScopeFromDescriptions,
    resolveClassChain,
    type ClassType,
    type EnumType
} from "@mdeo/language-metamodel";
import { AssociationEndCache } from "./associationEndCache.js";

const { DefaultScopeProvider, AstUtils, EMPTY_SCOPE } = sharedImport("langium");

/**
 * The scope provider for the Model language.
 * Handles scoping for object instance classes, properties, enum values, and links.
 */
export class ModelScopeProvider extends DefaultScopeProvider {
    /**
     * The AST reflection service for type checking and model introspection.
     */
    private readonly astReflection: AstReflection;

    /**
     * Cache for association end lookups.
     */
    private readonly associationEndCache: AssociationEndCache;

    /**
     * The Langium documents service for accessing imported files.
     */
    private readonly documents: LangiumDocuments;

    /**
     * The description provider for creating AST node descriptions.
     */
    private readonly descriptionProvider: AstNodeDescriptionProvider;

    /**
     * Constructs a new ModelScopeProvider.
     * @param services The extended Langium services.
     */
    constructor(services: ExtendedLangiumServices) {
        super(services);
        this.astReflection = services.shared.AstReflection;
        this.associationEndCache = new AssociationEndCache(services);
        this.documents = services.shared.workspace.LangiumDocuments;
        this.descriptionProvider = services.workspace.AstNodeDescriptionProvider;
    }

    /**
     * Gets the scope for a given reference context.
     *
     * @param context The reference context
     * @returns The scope for the reference
     */
    override getScope(context: ReferenceInfo): Scope {
        const document = AstUtils.getDocument(context.container);

        if (context.property === "class") {
            return this.getObjectClassScope(context, document);
        }
        if (context.property === "name" && this.astReflection.isInstance(context.container, PropertyAssignment)) {
            return this.getPropertyNameScope(context);
        }
        if (context.property === "enumRef" && this.astReflection.isInstance(context.container, EnumValue)) {
            return this.getEnumRefScope(context, document);
        }
        if (context.property === "value" && this.astReflection.isInstance(context.container, EnumValue)) {
            return this.getEnumValueScope(context);
        }
        if (context.property === "property" && this.astReflection.isInstance(context.container, LinkEnd)) {
            return this.getLinkPropertyScope(context);
        }
        if (context.property === "object" && this.astReflection.isInstance(context.container, LinkEnd)) {
            return this.getObjectInstancesScope(context);
        }

        return EMPTY_SCOPE;
    }

    /**
     * Gets the scope for class references, wherever they occur in the document
     * (an object instance, or a class reference inside a plugin-contributed import
     * such as CSV, at any nesting depth). Resolves the imported metamodel file and
     * returns a scope with all accessible classes.
     *
     * @param context The reference context
     * @param document The current document
     * @returns A scope containing all accessible classes from the imported metamodel
     */
    private getObjectClassScope(context: ReferenceInfo, document: any): Scope {
        const model = AstUtils.getContainerOfType(context.container, (node): node is ModelType =>
            this.astReflection.isInstance(node, Model)
        );
        const metamodelImport = model?.import;
        const relativePath = metamodelImport?.file;

        if (relativePath == undefined) {
            return EMPTY_SCOPE;
        }

        const metamodelUri = resolveRelativePath(document, relativePath);
        const metamodelDoc = this.documents.getDocument(metamodelUri);

        if (metamodelDoc == undefined) {
            return EMPTY_SCOPE;
        }

        return getScopeFromMetamodelFile(metamodelDoc, this.documents, this.descriptionProvider);
    }

    /**
     * Gets the scope for property name references.
     *
     * @param context The reference context
     * @return The scope containing the properties of the object's class chain
     */
    private getPropertyNameScope(context: ReferenceInfo): Scope {
        let objectInstance = context.container.$container as ObjectInstanceType;
        // workaround for langium weirdness in completion mode
        if (this.astReflection.isInstance(objectInstance, PropertyAssignment)) {
            objectInstance = objectInstance.$container as ObjectInstanceType;
        }
        const classRef = objectInstance?.class?.ref as ClassType | undefined;
        if (!classRef) {
            return EMPTY_SCOPE;
        }
        const classChain = resolveClassChain(classRef, this.astReflection);
        return this.createScopeForNodes(classChain.flatMap((cls) => cls.properties));
    }

    /**
     * Gets the scope for enum references (the EnumName part of EnumName.Entry).
     * Returns all enums from the imported metamodel file.
     *
     * @param context The reference context
     * @param document The current document
     * @returns The scope containing all enums from the metamodel
     */
    private getEnumRefScope(context: ReferenceInfo, document: any): Scope {
        const model = AstUtils.getContainerOfType(context.container, (node) =>
            this.astReflection.isInstance(node, Model)
        ) as ModelType | undefined;
        const relativePath = model?.import?.file;
        if (relativePath == undefined) {
            return EMPTY_SCOPE;
        }
        const metamodelUri = resolveRelativePath(document, relativePath);
        const metamodelDoc = this.documents.getDocument(metamodelUri);
        if (metamodelDoc == undefined) {
            return EMPTY_SCOPE;
        }
        const exports = getExportedEntitiesFromMetamodelFile(metamodelDoc, this.documents);
        const enumDescriptions = Array.from(exports.enums).map((e) =>
            this.descriptionProvider.createDescription(e, e.name ?? "")
        );
        return createScopeFromDescriptions(enumDescriptions);
    }

    /**
     * Gets the scope for enum value references (the Entry part of EnumName.Entry).
     * Returns all entries of the enum referenced by enumRef.
     *
     * @param context The reference context
     * @returns The scope containing the enum entries
     */
    private getEnumValueScope(context: ReferenceInfo): Scope {
        const enumValue = context.container as EnumValueType;
        const enumRef = enumValue.enumRef?.ref as EnumType | undefined;
        if (enumRef != undefined) {
            return this.createScopeForNodes(enumRef.entries);
        }
        return EMPTY_SCOPE;
    }

    /**
     * Gets the scope for object instance references in links.
     */
    private getObjectInstancesScope(context: ReferenceInfo): Scope {
        const model = AstUtils.getContainerOfType(context.container, (node) =>
            this.astReflection.isInstance(node, Model)
        );
        if (model == undefined) {
            return EMPTY_SCOPE;
        }
        return this.createScopeForNodes(model.objects);
    }

    /**
     * Gets the scope for property references in link ends.
     *
     * This method now properly handles associations instead of just primitive properties.
     * It traverses the class chain and for each class, looks up all association ends
     * that reference that class in the metamodel.
     *
     * @param context The reference context
     * @returns The scope containing the association ends of the linked object's class chain
     */
    private getLinkPropertyScope(context: ReferenceInfo): Scope {
        const linkEnd = context.container as LinkEndType;
        const objectRef = linkEnd.object?.ref as ObjectInstanceType | undefined;

        if (objectRef == undefined) {
            return EMPTY_SCOPE;
        }

        const classRef = objectRef.class?.ref as ClassType | undefined;
        if (classRef == undefined) {
            return EMPTY_SCOPE;
        }

        const classChain = resolveClassChain(classRef, this.astReflection);

        const allAssociationEnds = classChain.flatMap((cls) => {
            return this.associationEndCache.getAssociationEndsForClass(cls);
        });

        const uniqueAssociationEnds = Array.from(new Map(allAssociationEnds.map((end) => [end.name, end])).values());

        return this.createScopeForNodes(uniqueAssociationEnds);
    }

    /**
     * Finds the property assignment containing the given node.
     */
    private findPropertyAssignment(node: any): PropertyAssignmentType | undefined {
        let current = node.$container;
        while (current) {
            if (this.astReflection.isInstance(current, PropertyAssignment)) {
                return current as PropertyAssignmentType;
            }
            current = current.$container;
        }
        return undefined;
    }

    /**
     * Gets enum entries from a property type.
     * Since EnumTypeReference now directly references Enum (no imports),
     * we can simply access the entries from the resolved enum.
     *
     * @param type The property type to get enum entries from
     * @returns A scope containing the enum entries, or EMPTY_SCOPE if not an enum type
     */
    private getEnumEntriesFromType(type: any): Scope {
        if (this.astReflection.isInstance(type, EnumTypeReference)) {
            const enumRef = type.enum?.ref as EnumType | undefined;
            if (enumRef != undefined) {
                return this.createScopeForNodes(enumRef.entries);
            }
        }
        return EMPTY_SCOPE;
    }
}
