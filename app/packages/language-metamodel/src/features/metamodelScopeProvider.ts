import { sharedImport, createLocalScope } from "@mdeo/language-shared";
import type { AstNodeDescriptionProvider, LangiumDocument, LangiumDocuments, ReferenceInfo, Scope } from "langium";
import { Class, ClassOrEnum, Enum, type MetaModelType } from "../grammar/metamodelTypes.js";
import type { AstReflection, ExtendedLangiumServices } from "@mdeo/language-common";
import { getScopeFromMetamodelFile } from "./importHelpers.js";

const { DefaultScopeProvider, AstUtils, EMPTY_SCOPE } = sharedImport("langium");

/**
 * The scope provider for the Metamodel language.
 *
 * This scope provider handles resolution of references to classes and enums,
 * supporting both local references and cross-file references via file imports.
 * The simplified import system imports entire files, making all exported
 * classes and enums from imported files available for reference.
 */
export class MetamodelScopeProvider extends DefaultScopeProvider {
    /**
     * The AST reflection service for type checking and model introspection.
     */
    private readonly astReflection: AstReflection;

    /**
     * The Langium documents service for accessing imported files.
     */
    private readonly documents: LangiumDocuments;

    /**
     * The description provider for creating AST node descriptions.
     */
    private readonly descriptionProvider: AstNodeDescriptionProvider;

    /**
     * Constructs a new MetamodelScopeProvider.
     *
     * @param services The extended Langium services
     */
    constructor(services: ExtendedLangiumServices) {
        super(services);
        this.astReflection = services.shared.AstReflection;
        this.documents = services.shared.workspace.LangiumDocuments;
        this.descriptionProvider = services.workspace.AstNodeDescriptionProvider;
    }

    /**
     * Gets the scope for a given reference.
     *
     * @param referenceInfo Information about the reference being resolved
     * @returns The scope containing valid target elements for the reference
     */
    override getScope(referenceInfo: ReferenceInfo): Scope {
        const document = AstUtils.getDocument(referenceInfo.container);
        const model = document.parseResult.value as MetaModelType;

        const refType = this.astReflection.getReferenceType(referenceInfo);

        if (refType === Class.name || refType === Enum.name || refType === ClassOrEnum.name) {
            return this.getClassOrEnumScope(referenceInfo, document, model);
        }

        return super.getScope(referenceInfo);
    }

    /**
     * Gets the scope for class or enum references.
     * Combines local elements with transitively imported elements from all imported files.
     *
     * @param referenceInfo Information about the reference being resolved
     * @param document The current document
     * @returns A scope containing all accessible classes and enums
     */
    private getClassOrEnumScope(referenceInfo: ReferenceInfo, document: LangiumDocument, _model: MetaModelType): Scope {
        const importedScope = this.getImportedEntitiesScope(document);

        return createLocalScope(referenceInfo, document, this.astReflection, importedScope);
    }

    /**
     * Gets a scope containing all entities from imported files.
     * Uses the import helper to recursively collect entities from all imports.
     *
     * @param document The current document
     * @returns A scope containing entities from all imported metamodel files
     */
    private getImportedEntitiesScope(document: LangiumDocument): Scope {
        const metamodel = document.parseResult.value as MetaModelType;
        if (metamodel.imports == undefined || metamodel.imports.length === 0) {
            return EMPTY_SCOPE;
        }

        return getScopeFromMetamodelFile(document, this.documents, this.descriptionProvider);
    }
}
