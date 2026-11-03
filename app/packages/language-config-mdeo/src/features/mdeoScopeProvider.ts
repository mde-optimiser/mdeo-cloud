import type { AstReflection, ExtendedLangiumServices } from "@mdeo/language-common";
import { sharedImport } from "@mdeo/language-shared";
import type { AstNodeDescriptionProvider, LangiumDocuments, ReferenceInfo, Scope } from "langium";
import {
    Enum,
    getScopeFromMetamodelFile,
    resolveClassChain,
    type ClassType,
    type EnumType
} from "@mdeo/language-metamodel";
import { AssociationEndCache } from "@mdeo/language-model";
import {
    ClassMutation,
    EdgeMutation,
    EnumLiteral,
    type EdgeMutationType,
    type EnumLiteralType
} from "../grammar/mdeoTypes.js";
import type { MdeoMetamodelResolver } from "./mdeoMetamodelResolver.js";
import type { MdeoAdditionalServices } from "../mdeoPlugin.js";

const { DefaultScopeProvider, AstUtils, EMPTY_SCOPE, StreamScope } = sharedImport("langium");

/**
 * Scope provider for the config-mdeo language.
 * Handles scoping for:
 * - Class references in mutation operators (from metamodel)
 * - Edge references in edge mutation operators (from class properties)
 * - Enum and enum entry references in the literals of operator arguments (from metamodel)
 */
export class MdeoScopeProvider extends DefaultScopeProvider {
    /**
     * The AST reflection service for type checking.
     */
    protected readonly astReflection: AstReflection;

    /**
     * The Langium documents service for accessing imported files.
     */
    protected readonly documents: LangiumDocuments;

    /**
     * Cache for association end lookups.
     */
    private readonly associationEndCache: AssociationEndCache;

    /**
     * The description provider for creating AST node descriptions.
     */
    private readonly descriptionProvider: AstNodeDescriptionProvider;

    /**
     * The metamodel resolver service.
     */
    private readonly metamodelResolver: MdeoMetamodelResolver;

    /**
     * Constructs a new MdeoScopeProvider.
     *
     * @param services The extended Langium services with MDEO additions
     */
    constructor(services: ExtendedLangiumServices & MdeoAdditionalServices) {
        super(services);
        this.astReflection = services.shared.AstReflection;
        this.documents = services.shared.workspace.LangiumDocuments;
        this.associationEndCache = new AssociationEndCache(services);
        this.descriptionProvider = services.workspace.AstNodeDescriptionProvider;
        this.metamodelResolver = services.MdeoMetamodelResolver;
    }

    /**
     * Gets the scope for a given reference context.
     *
     * @param context The reference context
     * @returns The scope for the reference
     */
    override getScope(context: ReferenceInfo): Scope {
        if (
            context.property === "class" &&
            (this.astReflection.isInstance(context.container, ClassMutation) ||
                this.astReflection.isInstance(context.container, EdgeMutation))
        ) {
            return this.getClassScope(context);
        }

        if (context.property === "edge" && this.astReflection.isInstance(context.container, EdgeMutation)) {
            return this.getEdgeScope(context);
        }

        if (this.astReflection.isInstance(context.container, EnumLiteral)) {
            if (context.property === "enum") {
                return this.getEnumScope(context);
            }
            if (context.property === "entry") {
                return this.getEnumEntryScope(context);
            }
        }

        return EMPTY_SCOPE;
    }

    /**
     * Gets the scope for class references in mutation operators.
     * Returns classes from the metamodel defined in the problem section.
     *
     * @param context The reference context
     * @returns Scope with metamodel classes
     */
    private getClassScope(context: ReferenceInfo): Scope {
        return this.getMetamodelScope(context);
    }

    /**
     * Gets the scope of all classes and enums of the metamodel defined in the problem section,
     * including the ones of the metamodels it imports.
     *
     * @param context The reference context
     * @returns Scope with metamodel classes and enums
     */
    private getMetamodelScope(context: ReferenceInfo): Scope {
        const document = AstUtils.getDocument(context.container);
        const uri = this.metamodelResolver.getMetamodelUri(document);
        if (uri == undefined) {
            return EMPTY_SCOPE;
        }

        const targetDoc = this.documents.getDocument(uri);
        if (targetDoc == undefined) {
            return EMPTY_SCOPE;
        }

        return getScopeFromMetamodelFile(targetDoc, this.documents, this.descriptionProvider);
    }

    /**
     * Gets the scope for the enum of an enum literal (`Kind` of `Kind.SERVICE`): the enums of the
     * problem section's metamodel.
     *
     * @param context The reference context
     * @returns Scope with metamodel enums
     */
    private getEnumScope(context: ReferenceInfo): Scope {
        const enums = this.getMetamodelScope(context)
            .getAllElements()
            .filter((description) => this.astReflection.isSubtype(description.type, Enum.name));
        return new StreamScope(enums);
    }

    /**
     * Gets the scope for the entry of an enum literal (`SERVICE` of `Kind.SERVICE`): the entries
     * of the referenced enum.
     *
     * @param context The reference context
     * @returns Scope with the enum's entries
     */
    private getEnumEntryScope(context: ReferenceInfo): Scope {
        const literal = context.container as EnumLiteralType;
        const enumRef = literal.enum?.ref as EnumType | undefined;
        if (enumRef == undefined) {
            return EMPTY_SCOPE;
        }
        return this.createScopeForNodes(enumRef.entries ?? []);
    }

    /**
     * Gets the scope for edge references in edge mutation operators.
     * Returns the association ends of the referenced class, including inherited ones: operators
     * are generated for links only, never for attributes.
     *
     * @param context The reference context
     * @returns Scope with the class's association ends
     */
    private getEdgeScope(context: ReferenceInfo): Scope {
        const edgeMutation = context.container as EdgeMutationType;
        const classRef = edgeMutation.class?.ref as ClassType | undefined;

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
}
