import {
    generateClassTypes,
    generateEnumTypes,
    ExpressionTypeSystem,
    IterableType,
    extractMetamodelEntities,
    DefaultCollectionTypeFactory,
    TypePartialTypeSystem,
    type ClassType,
    type ExpressionTypirServices,
    type PrimitiveTypes,
    typeRef,
    MetamodelPartialTypeSystem
} from "@mdeo/language-expression";
import type { TypirLangiumSpecifics } from "typir-langium";
import type { LangiumDocument, LangiumDocuments, URI } from "langium";
import { resolveRelativePath, sharedImport } from "@mdeo/language-shared";
import { getExportedEntitiesByPath } from "@mdeo/language-metamodel";
import {
    expressionTypes,
    ModelTransformation,
    typeTypes,
    type ModelTransformationType
} from "../../grammar/modelTransformationTypes.js";
import { ModelTransformationPartialTypeSystem } from "./modelTransformationPartialTypeSystem.js";
import { ModelTransformationBagType } from "../stdlib/collections/Bag.js";
import { ModelTransformationCollectionType } from "../stdlib/collections/Collection.js";
import { ModelTransformationListType } from "../stdlib/collections/List.js";
import { ModelTransformationOrderedCollectionType } from "../stdlib/collections/OrderedCollection.js";
import { ModelTransformationOrderedSetType } from "../stdlib/collections/OrderedSet.js";
import { ModelTransformationSetType } from "../stdlib/collections/Set.js";
import { ModelTransformationAnyType } from "../stdlib/primitives/Any.js";
import { modelTransformationBooleanType } from "../stdlib/primitives/boolean.js";
import { modelTransformationDoubleType } from "../stdlib/primitives/double.js";
import { modelTransformationFloatType } from "../stdlib/primitives/float.js";
import { modelTransformationIntType } from "../stdlib/primitives/int.js";
import { modelTransformationLongType } from "../stdlib/primitives/long.js";
import { modelTransformationModelType } from "../stdlib/primitives/model.js";
import { modelTransformationStringType } from "../stdlib/primitives/string.js";

const { AstUtils } = sharedImport("langium");

/**
 * Type system for the Model Transformation language.
 * Provides type inference and validation for transformation expressions.
 */
export class ModelTransformationTypeSystem extends ExpressionTypeSystem<TypirLangiumSpecifics> {
    /**
     * Creates an instance of ModelTransformationTypeSystem.
     */
    constructor() {
        super(
            {
                Any: ModelTransformationAnyType,
                int: modelTransformationIntType,
                long: modelTransformationLongType,
                float: modelTransformationFloatType,
                double: modelTransformationDoubleType,
                string: modelTransformationStringType,
                boolean: modelTransformationBooleanType,
                Iterable: IterableType,
                additionalTypes: [
                    ModelTransformationCollectionType,
                    ModelTransformationOrderedCollectionType,
                    ModelTransformationListType,
                    ModelTransformationSetType,
                    ModelTransformationBagType,
                    ModelTransformationOrderedSetType,
                    modelTransformationModelType
                ],
                lambdaSuperTypes: [{ package: "builtin", type: ModelTransformationAnyType.name }],
                createListType: (elementType) => typeRef("builtin", "List").withTypeArgs({ T: elementType }).build()
            },
            expressionTypes,
            []
        );
    }

    /**
     * Returns the primitive types configuration.
     *
     * @returns The primitive types used by the type system.
     */
    getPrimitiveTypes(): PrimitiveTypes {
        return this.primitiveTypes;
    }

    /**
     * Initializes extended type system components.
     * Registers partial type systems for specific language constructs.
     *
     * @param typir The typir services to initialize with.
     */
    protected override onInitializeExtended(typir: ExpressionTypirServices<TypirLangiumSpecifics>): void {
        const typePartialTypeSystem = new TypePartialTypeSystem<TypirLangiumSpecifics>(
            typir,
            typeTypes,
            this.nullablePrimitiveTypes.Any
        );
        const modelTransformationPartialTypeSystem = new ModelTransformationPartialTypeSystem(
            typir,
            this.primitiveTypes
        );
        const metamodelPartialTypeSystem = new MetamodelPartialTypeSystem(typir, undefined);

        metamodelPartialTypeSystem.registerRules();
        typePartialTypeSystem.registerRules();
        modelTransformationPartialTypeSystem.registerRules();
    }

    /**
     * Called when a new AST node is encountered during document processing.
     * Registers metamodel class types in Typir when processing a ModelTransformation root.
     *
     * @param languageNode The AST node being processed
     * @param typir The extended Typir services instance
     */
    protected override onNewAstNodeExtended(
        languageNode: TypirLangiumSpecifics["LanguageType"],
        typir: ExpressionTypirServices<TypirLangiumSpecifics>
    ): void {
        const reflection = typir.langium.LangiumServices.AstReflection;
        if (!reflection.isInstance(languageNode, ModelTransformation)) {
            return;
        }

        const transformation = languageNode as ModelTransformationType;
        const document = AstUtils.getDocument(transformation);

        const metamodelDoc = this.loadMetamodelSync(document, transformation, typir);
        if (metamodelDoc == undefined) {
            return;
        }

        const langiumDocuments = typir.langium.LangiumServices.workspace.LangiumDocuments;

        const entitiesByPath = getExportedEntitiesByPath(metamodelDoc, langiumDocuments);

        for (const docEntities of entitiesByPath) {
            const absolutePath = docEntities.absolutePath;

            const { classes: classInfos, enums: enumInfos } = extractMetamodelEntities(
                docEntities.classes,
                docEntities.enums,
                reflection,
                DefaultCollectionTypeFactory,
                absolutePath
            );

            const classTypeResult = generateClassTypes(classInfos, absolutePath);
            this.registerClassTypes(classTypeResult.types, typir);

            const enumTypeResult = generateEnumTypes(enumInfos, absolutePath);
            this.registerEnumTypes(enumTypeResult.types, typir);
            this.registerEnumTypes(enumTypeResult.containerTypes, typir);
        }
    }

    /**
     * Loads the metamodel document synchronously.
     * Assumes the metamodel has been pre-loaded during external reference resolution.
     *
     * @param document The transformation document
     * @param transformation The transformation AST node
     * @param typir The Typir services for accessing shared Langium services
     * @returns The metamodel AST or undefined if not found
     */
    private loadMetamodelSync(
        document: LangiumDocument,
        transformation: ModelTransformationType,
        typir: ExpressionTypirServices<TypirLangiumSpecifics>
    ): LangiumDocument | undefined {
        const importFile = transformation.import?.file;
        if (importFile == undefined) {
            return undefined;
        }

        const metamodelUri = this.resolveMetamodelUri(document, importFile);
        const documents = this.getDocuments(typir);
        const metamodelDoc = documents.getDocument(metamodelUri);

        return metamodelDoc;
    }

    /**
     * Resolves the relative import path to an absolute URI.
     *
     * @param document The source document
     * @param importPath The relative import path
     * @returns The resolved URI
     */
    private resolveMetamodelUri(document: LangiumDocument, importPath: string): URI {
        return resolveRelativePath(document, importPath);
    }

    /**
     * Gets the LangiumDocuments service from the Typir services.
     *
     * @param typir The Typir services
     * @returns The LangiumDocuments service
     */
    private getDocuments(typir: ExpressionTypirServices<TypirLangiumSpecifics>): LangiumDocuments {
        return (typir.langium.LangiumServices as { workspace: { LangiumDocuments: LangiumDocuments } }).workspace
            .LangiumDocuments;
    }

    /**
     * Registers the generated class types in the Typir TypeDefinitions service.
     *
     * @param classTypes The ClassType definitions to register
     * @param typir The Typir services
     */
    private registerClassTypes(classTypes: ClassType[], typir: ExpressionTypirServices<TypirLangiumSpecifics>): void {
        const typeDefinitions = typir.TypeDefinitions;
        for (const classType of classTypes) {
            if (typeDefinitions.getClassTypeIfExisting(classType.name, classType.package) == undefined) {
                typeDefinitions.addClassType(classType);
            }
        }
    }

    /**
     * Registers class types in the Typir TypeDefinitions service.
     *
     * @param types The ClassType definitions to register
     * @param typir The Typir services
     */
    private registerEnumTypes(types: ClassType[], typir: ExpressionTypirServices<TypirLangiumSpecifics>): void {
        const typeDefinitions = typir.TypeDefinitions;
        for (const classType of types) {
            if (typeDefinitions.getClassTypeIfExisting(classType.name, classType.package) == undefined) {
                typeDefinitions.addClassType(classType);
            }
        }
    }
}
