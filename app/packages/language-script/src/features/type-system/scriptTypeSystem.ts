import {
    AnyType,
    BagType,
    booleanType,
    CollectionType,
    doubleType,
    ExpressionTypeSystem,
    floatType,
    intType,
    IterableType,
    ListType,
    longType,
    OrderedCollectionType,
    OrderedSetType,
    ReadonlyBagType,
    ReadonlyCollectionType,
    ReadonlyListType,
    ReadonlyOrderedCollectionType,
    ReadonlyOrderedSetType,
    ReadonlySetType,
    SetType,
    StatementPartialTypeSystem,
    stringType,
    TypePartialTypeSystem,
    typeRef,
    generateClassTypes,
    generateEnumTypes,
    extractMetamodelEntities,
    ReadonlyCollectionTypeFactory,
    type ClassType,
    getClassPackage,
    getEnumPackage,
    getClassContainerPackage,
    getEnumContainerPackage
} from "@mdeo/language-expression";
import { expressionTypes, statementTypes, typeTypes, Script, type ScriptType } from "../../grammar/scriptTypes.js";
import { ScriptPartialTypeSystem } from "./scriptPartialTypeSystem.js";
import type { ResolvedScriptContributionPlugins } from "../../plugin/scriptContributionPlugin.js";
import type { ScriptTypirServices, ScriptTypirSpecifics } from "../../plugin.js";
import { stdlibGlobalFunctions } from "../stdlib/globalFunctions.js";
import { resolveRelativePath, sharedImport } from "@mdeo/language-shared";
import { getExportedEntitiesByPath } from "@mdeo/language-metamodel";
import type { LangiumDocument, LangiumDocuments, URI } from "langium";

const { AstUtils } = sharedImport("langium");

/**
 * The type system for the Script language.
 */
export class ScriptTypeSystem extends ExpressionTypeSystem<ScriptTypirSpecifics> {
    /**
     * Creates an instance of ScriptTypeSystem.
     *
     * @param plugins The contribution plugins for the Script language.
     */
    constructor(private readonly plugins: ResolvedScriptContributionPlugins) {
        super(
            {
                Any: AnyType,
                int: intType,
                long: longType,
                float: floatType,
                double: doubleType,
                string: stringType,
                boolean: booleanType,
                Iterable: IterableType,
                additionalTypes: [
                    CollectionType,
                    ReadonlyCollectionType,
                    OrderedCollectionType,
                    ReadonlyOrderedCollectionType,
                    ListType,
                    ReadonlyListType,
                    SetType,
                    ReadonlySetType,
                    BagType,
                    ReadonlyBagType,
                    OrderedSetType,
                    ReadonlyOrderedSetType
                ],
                lambdaSuperTypes: [{ package: "builtin", type: AnyType.name }],
                createListType: (elementType) => typeRef("builtin", "List").withTypeArgs({ T: elementType }).build()
            },
            expressionTypes,
            [
                ...stdlibGlobalFunctions,
                ...[...plugins.functions.entries()].map(([name, func]) => ({
                    name,
                    isProperty: false as const,
                    type: func.function
                }))
            ]
        );
    }

    protected override onInitializeExtended(typir: ScriptTypirServices): void {
        const statementPartialTypeSystem = new StatementPartialTypeSystem<ScriptTypirSpecifics>(
            typir,
            statementTypes,
            this.expressionTypes,
            this.primitiveTypes,
            this.nullablePrimitiveTypes,
            this.defaultTypeConfig.Iterable
        );
        statementPartialTypeSystem.registerRules();

        const typePartialTypeSystem = new TypePartialTypeSystem<ScriptTypirSpecifics>(
            typir,
            typeTypes,
            this.nullablePrimitiveTypes.Any
        );
        typePartialTypeSystem.registerRules();

        const scriptPartialTypeSystem = new ScriptPartialTypeSystem(typir, this.primitiveTypes, this.plugins);
        scriptPartialTypeSystem.registerRules();
    }

    /**
     * Called when a new AST node is encountered during document processing.
     * Registers metamodel class and enum types in Typir when processing a Script root.
     *
     * @param languageNode The AST node being processed
     * @param typir The extended Typir services instance
     */
    protected override onNewAstNodeExtended(
        languageNode: ScriptTypirSpecifics["LanguageType"],
        typir: ScriptTypirServices
    ): void {
        const reflection = typir.langium.LangiumServices.AstReflection;
        if (!reflection.isInstance(languageNode, Script)) {
            return;
        }

        const document = AstUtils.getDocument(languageNode);

        const metamodelDoc = this.loadMetamodelSync(document, languageNode, typir);
        if (metamodelDoc == undefined) {
            return;
        }

        const langiumDocuments = typir.langium.LangiumServices.workspace.LangiumDocuments;

        const entitiesByPath = getExportedEntitiesByPath(metamodelDoc, langiumDocuments);

        const classPackages: string[] = [];
        const enumPackages: string[] = [];
        const classContainerPackages: string[] = [];
        const enumContainerPackages: string[] = [];

        for (const docEntities of entitiesByPath) {
            const absolutePath = docEntities.absolutePath;
            classPackages.push(getClassPackage(absolutePath));
            enumPackages.push(getEnumPackage(absolutePath));
            classContainerPackages.push(getClassContainerPackage(absolutePath));
            enumContainerPackages.push(getEnumContainerPackage(absolutePath));

            const { classes: classInfos, enums: enumInfos } = extractMetamodelEntities(
                docEntities.classes,
                docEntities.enums,
                reflection,
                ReadonlyCollectionTypeFactory,
                absolutePath
            );

            const classTypeResult = generateClassTypes(classInfos, absolutePath);
            this.registerClassTypes(classTypeResult.types, typir);
            this.registerClassTypes(classTypeResult.containerTypes, typir);

            const enumTypeResult = generateEnumTypes(enumInfos, absolutePath);
            this.registerEnumTypes(enumTypeResult.types, typir);
            this.registerEnumTypes(enumTypeResult.containerTypes, typir);
        }
    }

    /**
     * Loads the metamodel document synchronously.
     * Assumes the metamodel has been pre-loaded during external reference resolution.
     *
     * @param document The script document
     * @param script The script AST node
     * @param typir The Typir services for accessing shared Langium services
     * @returns The metamodel AST or undefined if not found
     */
    private loadMetamodelSync(
        document: LangiumDocument,
        script: ScriptType,
        typir: ScriptTypirServices
    ): LangiumDocument | undefined {
        const importFile = script.metamodelImport?.file;
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
    private getDocuments(typir: ScriptTypirServices): LangiumDocuments {
        return (typir.langium.LangiumServices as { workspace: { LangiumDocuments: LangiumDocuments } }).workspace
            .LangiumDocuments;
    }

    /**
     * Registers the generated class types in the Typir TypeDefinitions service.
     *
     * @param classTypes The ClassType definitions to register
     * @param typir The Typir services
     */
    private registerClassTypes(classTypes: ClassType[], typir: ScriptTypirServices): void {
        const typeDefinitions = typir.TypeDefinitions;
        for (const classType of classTypes) {
            if (typeDefinitions.getClassTypeIfExisting(classType.name, classType.package) == undefined) {
                typeDefinitions.addClassType(classType);
            }
        }
    }

    /**
     * Registers enum types in the Typir TypeDefinitions service.
     *
     * @param types The ClassType definitions to register
     * @param typir The Typir services
     */
    private registerEnumTypes(types: ClassType[], typir: ScriptTypirServices): void {
        const typeDefinitions = typir.TypeDefinitions;
        for (const classType of types) {
            if (typeDefinitions.getClassTypeIfExisting(classType.name, classType.package) == undefined) {
                typeDefinitions.addClassType(classType);
            }
        }
    }
}
