import type { AstReflection, ExtendedLangiumServices } from "@mdeo/language-common";
import {
    sharedImport,
    isImportReference,
    getExportedEntitiesFromGlobalScope,
    getImportedEntitiesFromCurrentFile
} from "@mdeo/language-shared";
import type { AstNodeDescriptionProvider, LangiumDocuments, ReferenceInfo, Scope } from "langium";
import { getScopeFromMetamodelFile, resolveClassChain, type ClassType } from "@mdeo/language-metamodel";
import {
    configOptimizationFileScopingConfig,
    ConstraintReference,
    Objective,
    Refinement,
    type RefinementType,
    type GoalSectionType,
    GoalSection
} from "../grammar/optimizationTypes.js";
import { AssociationEndCache } from "@mdeo/language-model";
import { findProblemSection, getMetamodelUri } from "./util.js";

const { DefaultScopeProvider, AstUtils, EMPTY_SCOPE } = sharedImport("langium");

/**
 * Scope provider for the config-optimization language.
 * Handles scoping for:
 * - Import references (functions from script files)
 * - Constraint/Objective function references (from imports)
 * - Class references in refinements (from metamodel)
 * - Field references in refinements (from class properties)
 */
export class OptimizationScopeProvider extends DefaultScopeProvider {
    /**
     * The AST reflection service for type checking (extended version with isInstance).
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
     * Constructs a new OptimizationScopeProvider.
     *
     * @param services The extended Langium services.
     */
    constructor(services: ExtendedLangiumServices) {
        super(services);
        this.astReflection = services.shared.AstReflection;
        this.documents = services.shared.workspace.LangiumDocuments;
        this.associationEndCache = new AssociationEndCache(services);
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

        if (isImportReference(context, configOptimizationFileScopingConfig)) {
            return getExportedEntitiesFromGlobalScope(
                document,
                context,
                configOptimizationFileScopingConfig,
                this.indexManager
            );
        }

        if (
            context.property === "constraint" &&
            this.astReflection.isInstance(context.container, ConstraintReference)
        ) {
            return this.getFunctionScope(context);
        }

        if (context.property === "objective" && this.astReflection.isInstance(context.container, Objective)) {
            return this.getFunctionScope(context);
        }

        if (context.property === "class" && this.astReflection.isInstance(context.container, Refinement)) {
            return this.getClassScope(context);
        }

        if (context.property === "field" && this.astReflection.isInstance(context.container, Refinement)) {
            return this.getFieldScope(context);
        }

        return EMPTY_SCOPE;
    }

    /**
     * Gets the scope for function references in constraints and objectives.
     * Returns functions imported in the containing goal section.
     *
     * @param context The reference context
     * @returns Scope with imported functions
     */
    private getFunctionScope(context: ReferenceInfo): Scope {
        const goalSection = AstUtils.getContainerOfType(context.container, (node): node is GoalSectionType =>
            this.astReflection.isInstance(node, GoalSection)
        );
        if (goalSection == undefined) {
            return EMPTY_SCOPE;
        }
        return getImportedEntitiesFromCurrentFile(goalSection.imports, this.nameProvider, this.descriptions);
    }

    /**
     * Gets the scope for class references in refinements.
     * Returns classes from the metamodel defined in the problem section.
     *
     * @param context The reference context
     * @returns Scope with metamodel classes
     */
    private getClassScope(context: ReferenceInfo): Scope {
        const document = AstUtils.getDocument(context.container);
        const problemSection = findProblemSection(context.container);
        if (problemSection == undefined) {
            return EMPTY_SCOPE;
        }

        const targetUri = getMetamodelUri(document, problemSection);
        if (targetUri == undefined) {
            return EMPTY_SCOPE;
        }

        const targetDoc = this.documents.getDocument(targetUri);
        if (targetDoc == undefined) {
            return EMPTY_SCOPE;
        }

        return getScopeFromMetamodelFile(targetDoc, this.documents, this.descriptionProvider);
    }

    /**
     * Gets the scope for field references in refinements.
     * Returns the association ends of the referenced class, including inherited ones so that the
     * validator can point to the declaring class; attributes cannot be refined.
     *
     * @param context The reference context
     * @returns Scope with the class's association ends
     */
    private getFieldScope(context: ReferenceInfo): Scope {
        const refinement = context.container as RefinementType;
        const classRef = refinement.class?.ref as ClassType | undefined;

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
