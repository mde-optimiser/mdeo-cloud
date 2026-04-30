import type {
    ValidationAcceptor,
    ValidationChecks,
    AstNode,
    LangiumDocuments,
    AstNodeDescriptionProvider,
    LangiumDocument
} from "langium";
import type { AstReflection, ExtendedLangiumServices } from "@mdeo/language-common";
import {
    Association,
    Class,
    Enum,
    MetaModel,
    RangeMultiplicity,
    SingleMultiplicity,
    MetamodelAssociationOperators,
    type AssociationEndType,
    type AssociationType,
    type ClassType,
    type EnumEntryType,
    type EnumType,
    type FileImportType,
    type MetaModelType,
    type MultiplicityType,
    type PropertyType
} from "../grammar/metamodelTypes.js";
import { resolveClassChain } from "../features/semanticInformation.js";
import { sharedImport } from "@mdeo/language-shared";
import { getExportedEntitiesFromMetamodelFile, resolveImportedDocument } from "../features/importHelpers.js";

const { MultiMap, AstUtils } = sharedImport("langium");

/**
 * Issue codes for metamodel validator quick fixes.
 */
export const MetamodelIssueCodes = {
    AssociationMissingSourceName: "association-missing-source-name",
    AssociationMissingTargetName: "association-missing-target-name",
    AssociationExtraSourceName: "association-extra-source-name",
    AssociationExtraTargetName: "association-extra-target-name"
} as const;

/**
 * Interface mapping for metamodel AST types used in validation checks.
 */
interface MetamodelAstTypes {
    MetaModel: MetaModelType;
    Class: ClassType;
    Enum: EnumType;
    EnumEntry: EnumEntryType;
    Property: PropertyType;
    Association: AssociationType;
}

/**
 * Registers validation checks for the metamodel language.
 *
 * @param services The language services
 */
export function registerMetamodelValidationChecks(services: ExtendedLangiumServices): void {
    const registry = services.validation.ValidationRegistry;
    const validator = new MetamodelValidator(services);

    const checks: ValidationChecks<MetamodelAstTypes> = {
        MetaModel: validator.validateMetaModel.bind(validator),
        EnumEntry: validator.validateEnumEntryUniqueness.bind(validator),
        Property: validator.validateProperty.bind(validator),
        Association: validator.validateAssociation.bind(validator)
    };

    registry.register(checks, validator);
}

/**
 * Validator for metamodel language constructs.
 */
export class MetamodelValidator {
    private readonly reflection: AstReflection;
    private readonly documents: LangiumDocuments;
    private readonly descriptionProvider: AstNodeDescriptionProvider;

    constructor(private readonly services: ExtendedLangiumServices) {
        this.reflection = services.shared.AstReflection;
        this.documents = services.shared.workspace.LangiumDocuments;
        this.descriptionProvider = services.workspace.AstNodeDescriptionProvider;
    }

    /**
     * Validates the entire metamodel for class/enum name uniqueness and import cycles.
     * This validation considers:
     * - Classes/enums declared directly in the file
     * - Imported classes/enums from all imported files (transitively)
     *
     * @param metaModel The metamodel to validate
     * @param accept The validation acceptor for reporting errors
     */
    validateMetaModel(metaModel: MetaModelType, accept: ValidationAcceptor): void {
        this.validateClassEnumNameUniqueness(metaModel, accept);
        this.validateNoCyclicImports(metaModel, accept);
        this.validateNoClassHierarchyCycles(metaModel, accept);
    }

    /**
     * Validates that class and enum names are unique within the metamodel.
     * Checks for conflicts between local entities and imported entities.
     *
     * @param metaModel The metamodel to validate
     * @param accept The validation acceptor for reporting errors
     */
    private validateClassEnumNameUniqueness(metaModel: MetaModelType, accept: ValidationAcceptor): void {
        this.checkLocalNameConflicts(metaModel, accept);
        this.checkImportedNameConflicts(metaModel, accept);
    }

    /**
     * Checks for duplicate names among local classes and enums.
     *
     * @param metaModel The metamodel to validate
     * @param accept The validation acceptor for reporting errors
     * @returns Set of locally defined entity names
     */
    private checkLocalNameConflicts(metaModel: MetaModelType, accept: ValidationAcceptor): Set<string> {
        const nameToNodes = new MultiMap<string, AstNode>();
        const localNames = new Set<string>();

        for (const element of metaModel.elements ?? []) {
            if (this.reflection.isInstance(element, Class)) {
                if (element.name) {
                    nameToNodes.add(element.name, element);
                    localNames.add(element.name);
                }
            } else if (this.reflection.isInstance(element, Enum)) {
                if (element.name) {
                    nameToNodes.add(element.name, element);
                    localNames.add(element.name);
                }
            }
        }

        for (const [name, nodes] of nameToNodes.entriesGroupedByKey()) {
            if (nodes.length > 1) {
                for (const node of nodes) {
                    const nodeType = this.getNodeTypeDescription(node);
                    accept("error", `Duplicate ${nodeType} name: '${name}'.`, {
                        node,
                        property: this.getNameProperty(node)
                    });
                }
            }
        }

        return localNames;
    }

    /**
     * Checks for name conflicts between local entities and imported entities.
     * Same entity imported through different paths is NOT a conflict (deduplicated by node).
     * Different entities with the same name IS a conflict.
     *
     * @param metaModel The metamodel to validate
     * @param accept The validation acceptor for reporting errors
     */
    private checkImportedNameConflicts(metaModel: MetaModelType, accept: ValidationAcceptor): void {
        const document = AstUtils.getDocument(metaModel);
        if (document == undefined) {
            return;
        }

        const importedByName = this.collectImportedEntitiesByName(document, metaModel);

        this.reportLocalImportConflicts(metaModel, importedByName, accept);
        this.reportImportImportConflicts(importedByName, accept);
    }

    /**
     * Collects all imported entities grouped by name.
     * Uses a Map to deduplicate entities by their actual node identity.
     *
     * @param document The current document
     * @param metaModel The metamodel containing imports
     * @returns Map from entity name to Set of unique entities with that name
     */
    private collectImportedEntitiesByName(
        document: LangiumDocument,
        metaModel: MetaModelType
    ): Map<string, Set<ClassType | EnumType>> {
        const importedByName = new Map<string, Set<ClassType | EnumType>>();

        for (const importStmt of metaModel.imports ?? []) {
            const importedDoc = resolveImportedDocument(document, importStmt, this.documents);
            if (importedDoc == undefined) {
                continue;
            }

            const exports = getExportedEntitiesFromMetamodelFile(importedDoc, this.documents);

            this.addExportsToNameMap(exports.classes, importedByName);
            this.addExportsToNameMap(exports.enums, importedByName);
        }

        return importedByName;
    }

    /**
     * Adds exported entities to a name-to-entities map.
     *
     * @param exports Set of exported entities
     * @param nameMap Target map to add entities to
     */
    private addExportsToNameMap(
        exports: Set<ClassType | EnumType>,
        nameMap: Map<string, Set<ClassType | EnumType>>
    ): void {
        for (const entity of exports) {
            const name = entity.name;
            if (name == undefined) {
                continue;
            }

            if (!nameMap.has(name)) {
                nameMap.set(name, new Set());
            }
            nameMap.get(name)!.add(entity);
        }
    }

    /**
     * Reports conflicts between local entities and imported entities.
     *
     * @param metaModel The metamodel to validate
     * @param importedByName Map of imported entity names to entities
     * @param accept The validation acceptor
     */
    private reportLocalImportConflicts(
        metaModel: MetaModelType,
        importedByName: Map<string, Set<ClassType | EnumType>>,
        accept: ValidationAcceptor
    ): void {
        for (const element of metaModel.elements ?? []) {
            if (!this.reflection.isInstance(element, Class) && !this.reflection.isInstance(element, Enum)) {
                continue;
            }

            const name = element.name;
            if (name == undefined || !importedByName.has(name)) {
                continue;
            }

            const nodeType = this.getNodeTypeDescription(element);
            accept("error", `Local ${nodeType} '${name}' conflicts with an imported entity of the same name.`, {
                node: element,
                property: "name"
            });
        }
    }

    /**
     * Reports conflicts where different imported entities have the same name.
     *
     * @param importedByName Map of imported entity names to entities
     * @param accept The validation acceptor
     */
    private reportImportImportConflicts(
        importedByName: Map<string, Set<ClassType | EnumType>>,
        accept: ValidationAcceptor
    ): void {
        for (const [name, entities] of importedByName.entries()) {
            if (entities.size <= 1) {
                continue;
            }

            for (const entity of entities) {
                const nodeType = this.reflection.isInstance(entity, Class) ? "class" : "enum";
                accept(
                    "error",
                    `Imported ${nodeType} '${name}' conflicts with another imported entity of the same name.`,
                    {
                        node: entity,
                        property: "name"
                    }
                );
            }
        }
    }

    /**
     * Validates that no class (directly or transitively) extends itself.
     * Uses a single DFS pass over all local classes with 3-color marking
     * (unvisited / in-stack / done) to efficiently detect back edges.
     *
     * @param metaModel The metamodel to validate
     * @param accept The validation acceptor
     */
    private validateNoClassHierarchyCycles(metaModel: MetaModelType, accept: ValidationAcceptor): void {
        const allClasses: ClassType[] = [];
        for (const element of metaModel.elements ?? []) {
            if (this.reflection.isInstance(element, Class)) {
                allClasses.push(element as ClassType);
            }
        }

        // 0 = unvisited (WHITE), 1 = in current DFS path (GRAY), 2 = fully processed (BLACK)
        const color = new Map<ClassType, 0 | 1 | 2>();
        for (const cls of allClasses) {
            color.set(cls, 0);
        }

        const path: ClassType[] = [];
        const reportedCycle = new Set<ClassType>();

        const dfs = (cls: ClassType): void => {
            color.set(cls, 1);
            path.push(cls);

            for (const ext of cls.extensions?.extensions ?? []) {
                const parent = ext.class?.ref;
                if (parent == undefined) continue;

                const parentColor = color.get(parent);
                if (parentColor === 1) {
                    // Back edge: parent is still in the DFS stack → cycle detected.
                    // Report on all classes from parent to cls (inclusive) in the current path.
                    const cycleStart = path.indexOf(parent);
                    for (let i = cycleStart; i < path.length; i++) {
                        const cycleCls = path[i]!;
                        if (!reportedCycle.has(cycleCls)) {
                            reportedCycle.add(cycleCls);
                            accept("error", `Class '${cycleCls.name}' is part of a cyclic class hierarchy.`, {
                                node: cycleCls,
                                property: "name"
                            });
                        }
                    }
                } else if (parentColor === 0) {
                    dfs(parent);
                }
                // parentColor === 2 or undefined (external/imported class): fully processed, skip
            }

            path.pop();
            color.set(cls, 2);
        };

        for (const cls of allClasses) {
            if (color.get(cls) === 0) {
                dfs(cls);
            }
        }
    }

    /**
     * Validates that there are no cyclic import dependencies.
     * Cyclic imports are technically allowed for resolution but should warn users.
     * Also validates that import paths are non-empty and resolvable.
     *
     * @param metaModel The metamodel to validate
     * @param accept The validation acceptor
     */
    private validateNoCyclicImports(metaModel: MetaModelType, accept: ValidationAcceptor): void {
        const document = AstUtils.getDocument(metaModel);
        if (document == undefined) {
            return;
        }

        const currentPath: string[] = [document.uri.toString()];
        const visited = new Set<string>();

        for (const importStmt of metaModel.imports ?? []) {
            if (!this.validateImportPath(document, importStmt, accept)) {
                continue;
            }
            this.detectCyclicImport(document, importStmt, currentPath, visited, accept);
        }
    }

    /**
     * Validates that an import path is non-empty and resolvable.
     *
     * @param document The document containing the import
     * @param importStmt The import statement to validate
     * @param accept The validation acceptor for reporting errors
     * @returns True if the import path is valid and resolvable, false otherwise
     */
    private validateImportPath(
        document: LangiumDocument,
        importStmt: FileImportType,
        accept: ValidationAcceptor
    ): boolean {
        if (!importStmt.file || importStmt.file.trim().length === 0) {
            accept("error", "Import path cannot be empty", {
                node: importStmt,
                property: "file"
            });
            return false;
        }

        const importedDoc = resolveImportedDocument(document, importStmt, this.documents);
        if (importedDoc == undefined) {
            accept("error", `Cannot resolve import: '${importStmt.file}'`, {
                node: importStmt,
                property: "file"
            });
            return false;
        }

        return true;
    }

    /**
     * Recursively detects cyclic imports using DFS traversal.
     *
     * @param currentDocument The current document being traversed
     * @param importStmt The import statement to follow
     * @param currentPath Stack of URIs in the current traversal path
     * @param visited Set of fully processed document URIs
     * @param accept The validation acceptor
     */
    private detectCyclicImport(
        currentDocument: LangiumDocument,
        importStmt: FileImportType,
        currentPath: string[],
        visited: Set<string>,
        accept: ValidationAcceptor
    ): void {
        const importedDoc = resolveImportedDocument(currentDocument, importStmt, this.documents);
        if (importedDoc == undefined) {
            return;
        }

        const importedUri = importedDoc.uri.toString();

        if (currentPath.includes(importedUri)) {
            this.reportCyclicImport(importStmt, currentPath, importedUri, accept);
            return;
        }

        if (visited.has(importedUri)) {
            return;
        }

        currentPath.push(importedUri);
        const importedMetaModel = importedDoc.parseResult.value as MetaModelType;

        if (importedMetaModel != undefined) {
            for (const nestedImport of importedMetaModel.imports ?? []) {
                this.detectCyclicImport(importedDoc, nestedImport, currentPath, visited, accept);
            }
        }

        currentPath.pop();
        visited.add(importedUri);
    }

    /**
     * Reports a cyclic import warning with the cycle path.
     *
     * @param importStmt The import statement that creates the cycle
     * @param currentPath The current path in the traversal
     * @param cycleTarget The URI that completes the cycle
     * @param accept The validation acceptor
     */
    private reportCyclicImport(
        importStmt: FileImportType,
        currentPath: string[],
        cycleTarget: string,
        accept: ValidationAcceptor
    ): void {
        const cycleStartIdx = currentPath.indexOf(cycleTarget);
        const cyclePath = currentPath
            .slice(cycleStartIdx)
            .map((uri) => this.getFileNameFromUri(uri))
            .concat(this.getFileNameFromUri(cycleTarget));

        accept("warning", `Cyclic import detected: ${cyclePath.join(" → ")}`, {
            node: importStmt,
            property: "file"
        });
    }

    /**
     * Extracts the file name from a URI string.
     *
     * @param uri The URI string
     * @returns The file name portion of the URI
     */
    private getFileNameFromUri(uri: string): string {
        const parts = uri.split("/");
        return parts[parts.length - 1] ?? uri;
    }

    /**
     * Validates that enum entry names are unique within their parent enum.
     */
    validateEnumEntryUniqueness(entry: EnumEntryType, accept: ValidationAcceptor): void {
        const parentEnum = entry.$container;
        if (parentEnum == undefined || !this.reflection.isInstance(parentEnum, Enum)) {
            return;
        }

        const enumType = parentEnum as EnumType;
        const entryName = entry.name;
        if (!entryName) {
            return;
        }

        let count = 0;
        for (const e of enumType.entries ?? []) {
            if (e.name === entryName) {
                count++;
            }
        }

        if (count > 1) {
            accept("error", `Duplicate enum entry name: '${entryName}'.`, {
                node: entry,
                property: "name"
            });
        }
    }

    /**
     * Validates a property, including:
     * - Name uniqueness within class hierarchy
     * - Multiplicity format
     */
    validateProperty(property: PropertyType, accept: ValidationAcceptor): void {
        this.validatePropertyMultiplicity(property, accept);
        this.validatePropertyUniquenessInHierarchy(property, accept);
    }

    /**
     * Validates that a property name is unique within the class hierarchy,
     * including properties from parent classes and association ends.
     */
    private validatePropertyUniquenessInHierarchy(property: PropertyType, accept: ValidationAcceptor): void {
        const parentClass = property.$container;
        if (!parentClass || !this.reflection.isInstance(parentClass, Class)) {
            return;
        }

        const propertyName = property.name;
        if (!propertyName) {
            return;
        }

        const allPropertyNames = collectAllPropertyNames(parentClass, this.reflection);

        let count = 0;
        for (const name of allPropertyNames) {
            if (name === propertyName) {
                count++;
            }
        }

        if (count > 1) {
            accept("error", `Duplicate property name: '${propertyName}' in class hierarchy.`, {
                node: property,
                property: "name"
            });
        }
    }

    /**
     * Validates that multiplicity ranges are valid (upper >= lower).
     */
    private validatePropertyMultiplicity(property: PropertyType, accept: ValidationAcceptor): void {
        const multiplicity = property.multiplicity;
        if (!multiplicity) {
            return;
        }

        this.validateMultiplicityRange(multiplicity, property, accept);
    }

    /**
     * Validates a multiplicity: checks that bounds are non-negative, and for range
     * multiplicities also checks that the upper bound is >= the lower bound.
     */
    private validateMultiplicityRange(multiplicity: MultiplicityType, node: AstNode, accept: ValidationAcceptor): void {
        if (this.reflection.isInstance(multiplicity, SingleMultiplicity)) {
            const numericValue = multiplicity.numericValue;
            if (numericValue !== undefined && numericValue < 0) {
                accept("error", `Multiplicity value (${numericValue}) must not be negative.`, {
                    node,
                    property: "multiplicity"
                });
            }
        } else if (this.reflection.isInstance(multiplicity, RangeMultiplicity)) {
            const lower = multiplicity.lower;
            const upperNumeric = multiplicity.upperNumeric;

            if (lower < 0) {
                accept("error", `Multiplicity lower bound (${lower}) must not be negative.`, {
                    node,
                    property: "multiplicity"
                });
            }
            if (upperNumeric !== undefined && upperNumeric < 0) {
                accept("error", `Multiplicity upper bound (${upperNumeric}) must not be negative.`, {
                    node,
                    property: "multiplicity"
                });
            }

            if (multiplicity.upper === "*") {
                return;
            }

            if (upperNumeric !== undefined && upperNumeric < lower) {
                accept(
                    "error",
                    `Invalid multiplicity range: upper bound (${upperNumeric}) must be >= lower bound (${lower}).`,
                    { node, property: "multiplicity" }
                );
            }
        }
    }

    /**
     * Validates an association, including:
     * - Property name uniqueness at each end
     * - Correct property definition based on operator
     * - Composition multiplicity constraints
     */
    validateAssociation(association: AssociationType, accept: ValidationAcceptor): void {
        this.validateAssociationPropertyRequirements(association, accept);
        this.validateAssociationPropertyUniqueness(association, accept);
        this.validateAssociationEndMultiplicity(association.source, association, accept);
        this.validateAssociationEndMultiplicity(association.target, association, accept);
        this.validateCompositionMultiplicity(association, accept);
    }

    /**
     * Validates that association end property names don't conflict with existing properties
     * in the class hierarchy (including regular properties and other association ends).
     */
    private validateAssociationPropertyUniqueness(association: AssociationType, accept: ValidationAcceptor): void {
        if (association.source?.name) {
            const sourceClass = association.source.class?.ref;
            if (sourceClass) {
                const existingNames = this.collectPropertyNamesExcludingCurrentEnd(sourceClass, association, "source");
                if (existingNames.includes(association.source.name)) {
                    accept(
                        "error",
                        `Property name '${association.source.name}' conflicts with an existing property in the class hierarchy.`,
                        { node: association.source, property: "name" }
                    );
                }
            }
        }

        if (association.target?.name) {
            const targetClass = association.target.class?.ref;
            if (targetClass) {
                const existingNames = this.collectPropertyNamesExcludingCurrentEnd(targetClass, association, "target");
                if (existingNames.includes(association.target.name)) {
                    accept(
                        "error",
                        `Property name '${association.target.name}' conflicts with an existing property in the class hierarchy.`,
                        { node: association.target, property: "name" }
                    );
                }
            }
        }
    }

    /**
     * Collects all property names in the class hierarchy, excluding the current association end.
     */
    private collectPropertyNamesExcludingCurrentEnd(
        classType: ClassType,
        currentAssociation: AssociationType,
        currentEnd: "source" | "target"
    ): string[] {
        const propertyNames: string[] = [];
        const classChain = resolveClassChain(classType, this.reflection);

        for (const cls of classChain) {
            for (const prop of cls.properties ?? []) {
                if (prop.name) {
                    propertyNames.push(prop.name);
                }
            }

            const metaModel = getContainingMetaModel(cls, this.reflection);
            if (metaModel) {
                for (const element of metaModel.elements ?? []) {
                    if (this.reflection.isInstance(element, Association)) {
                        const assoc = element;

                        const isCurrentSourceEnd = assoc === currentAssociation && currentEnd === "source";
                        const isCurrentTargetEnd = assoc === currentAssociation && currentEnd === "target";

                        const sourceClass = assoc.source?.class?.ref;
                        if (sourceClass === cls && assoc.source?.name && !isCurrentSourceEnd) {
                            propertyNames.push(assoc.source.name);
                        }

                        const targetClass = assoc.target?.class?.ref;
                        if (targetClass === cls && assoc.target?.name && !isCurrentTargetEnd) {
                            propertyNames.push(assoc.target.name);
                        }
                    }
                }
            }
        }

        return propertyNames;
    }

    /**
     * Validates that properties are defined on the correct ends based on the association operator.
     *
     * Operator rules:
     * - --> (NAVIGABLE_TO_TARGET): left side needs property
     * - <-- (NAVIGABLE_TO_SOURCE): right side needs property
     * - <--> (BIDIRECTIONAL): both sides need properties
     * - *--> (COMPOSITION_SOURCE_NAVIGABLE_TARGET): both sides need properties
     * - *-- (COMPOSITION_SOURCE): right side needs property
     * - <--* (COMPOSITION_TARGET_NAVIGABLE_SOURCE): left side needs property
     * - --* (COMPOSITION_TARGET): left side needs property
     */
    private validateAssociationPropertyRequirements(association: AssociationType, accept: ValidationAcceptor): void {
        const operator = association.operator;
        const sourceHasProperty = !!association.source?.name;
        const targetHasProperty = !!association.target?.name;

        switch (operator) {
            case MetamodelAssociationOperators.NAVIGABLE_TO_TARGET:
                if (!sourceHasProperty) {
                    accept("error", `Association with '-->' requires a property name on the source (left) side.`, {
                        node: association,
                        property: "source",
                        data: {
                            code: MetamodelIssueCodes.AssociationMissingSourceName,
                            suggestedOperator: targetHasProperty
                                ? MetamodelAssociationOperators.NAVIGABLE_TO_SOURCE
                                : undefined
                        }
                    });
                }
                if (targetHasProperty) {
                    accept(
                        "error",
                        `Association with '-->' must not have a property name on the target (right) side.`,
                        {
                            node: association,
                            property: "target",
                            data: {
                                code: MetamodelIssueCodes.AssociationExtraTargetName,
                                suggestedOperator: sourceHasProperty
                                    ? MetamodelAssociationOperators.BIDIRECTIONAL
                                    : MetamodelAssociationOperators.NAVIGABLE_TO_SOURCE
                            }
                        }
                    );
                }
                break;

            case MetamodelAssociationOperators.NAVIGABLE_TO_SOURCE:
                if (!targetHasProperty) {
                    accept("error", `Association with '<--' requires a property name on the target (right) side.`, {
                        node: association,
                        property: "target",
                        data: {
                            code: MetamodelIssueCodes.AssociationMissingTargetName,
                            suggestedOperator: sourceHasProperty
                                ? MetamodelAssociationOperators.NAVIGABLE_TO_TARGET
                                : undefined
                        }
                    });
                }
                if (sourceHasProperty) {
                    accept("error", `Association with '<--' must not have a property name on the source (left) side.`, {
                        node: association,
                        property: "source",
                        data: {
                            code: MetamodelIssueCodes.AssociationExtraSourceName,
                            suggestedOperator: targetHasProperty
                                ? MetamodelAssociationOperators.BIDIRECTIONAL
                                : MetamodelAssociationOperators.NAVIGABLE_TO_TARGET
                        }
                    });
                }
                break;

            case MetamodelAssociationOperators.BIDIRECTIONAL:
                if (!sourceHasProperty) {
                    accept(
                        "error",
                        `Bidirectional association '<-->' requires a property name on the source (left) side.`,
                        {
                            node: association,
                            property: "source",
                            data: {
                                code: MetamodelIssueCodes.AssociationMissingSourceName,
                                suggestedOperator: targetHasProperty
                                    ? MetamodelAssociationOperators.NAVIGABLE_TO_SOURCE
                                    : undefined
                            }
                        }
                    );
                }
                if (!targetHasProperty) {
                    accept(
                        "error",
                        `Bidirectional association '<-->' requires a property name on the target (right) side.`,
                        {
                            node: association,
                            property: "target",
                            data: {
                                code: MetamodelIssueCodes.AssociationMissingTargetName,
                                suggestedOperator: sourceHasProperty
                                    ? MetamodelAssociationOperators.NAVIGABLE_TO_TARGET
                                    : undefined
                            }
                        }
                    );
                }
                break;

            case MetamodelAssociationOperators.COMPOSITION_SOURCE_NAVIGABLE_TARGET:
                if (!sourceHasProperty) {
                    accept(
                        "error",
                        `Composition association '*-->' requires a property name on the source (left) side.`,
                        {
                            node: association,
                            property: "source",
                            data: {
                                code: MetamodelIssueCodes.AssociationMissingSourceName,
                                suggestedOperator: targetHasProperty
                                    ? MetamodelAssociationOperators.COMPOSITION_SOURCE
                                    : undefined
                            }
                        }
                    );
                }
                if (!targetHasProperty) {
                    accept(
                        "error",
                        `Composition association '*-->' requires a property name on the target (right) side.`,
                        {
                            node: association,
                            property: "target",
                            data: {
                                code: MetamodelIssueCodes.AssociationMissingTargetName,
                                suggestedOperator: sourceHasProperty
                                    ? MetamodelAssociationOperators.COMPOSITION_TARGET
                                    : undefined
                            }
                        }
                    );
                }
                break;

            case MetamodelAssociationOperators.COMPOSITION_SOURCE:
                if (!targetHasProperty) {
                    accept(
                        "error",
                        `Composition association '*--' requires a property name on the target (right) side.`,
                        {
                            node: association,
                            property: "target",
                            data: {
                                code: MetamodelIssueCodes.AssociationMissingTargetName,
                                suggestedOperator: sourceHasProperty
                                    ? MetamodelAssociationOperators.COMPOSITION_TARGET
                                    : undefined
                            }
                        }
                    );
                }
                if (sourceHasProperty) {
                    accept(
                        "error",
                        `Composition association '*--' must not have a property name on the source (left) side.`,
                        {
                            node: association,
                            property: "source",
                            data: {
                                code: MetamodelIssueCodes.AssociationExtraSourceName,
                                suggestedOperator: targetHasProperty
                                    ? MetamodelAssociationOperators.COMPOSITION_SOURCE_NAVIGABLE_TARGET
                                    : MetamodelAssociationOperators.COMPOSITION_TARGET
                            }
                        }
                    );
                }
                break;

            case MetamodelAssociationOperators.COMPOSITION_TARGET_NAVIGABLE_SOURCE:
                if (!sourceHasProperty) {
                    accept(
                        "error",
                        `Composition association '<--*' requires a property name on the source (left) side.`,
                        {
                            node: association,
                            property: "source",
                            data: {
                                code: MetamodelIssueCodes.AssociationMissingSourceName,
                                suggestedOperator: targetHasProperty
                                    ? MetamodelAssociationOperators.COMPOSITION_SOURCE
                                    : undefined
                            }
                        }
                    );
                }
                if (!targetHasProperty) {
                    accept(
                        "error",
                        `Composition association '<--*' requires a property name on the target (right) side.`,
                        {
                            node: association,
                            property: "target",
                            data: {
                                code: MetamodelIssueCodes.AssociationMissingTargetName,
                                suggestedOperator: sourceHasProperty
                                    ? MetamodelAssociationOperators.COMPOSITION_TARGET
                                    : undefined
                            }
                        }
                    );
                }
                break;

            case MetamodelAssociationOperators.COMPOSITION_TARGET:
                if (!sourceHasProperty) {
                    accept(
                        "error",
                        `Composition association '--*' requires a property name on the source (left) side.`,
                        {
                            node: association,
                            property: "source",
                            data: {
                                code: MetamodelIssueCodes.AssociationMissingSourceName,
                                suggestedOperator: targetHasProperty
                                    ? MetamodelAssociationOperators.COMPOSITION_SOURCE
                                    : undefined
                            }
                        }
                    );
                }
                if (targetHasProperty) {
                    accept(
                        "error",
                        `Composition association '--*' must not have a property name on the target (right) side.`,
                        {
                            node: association,
                            property: "target",
                            data: {
                                code: MetamodelIssueCodes.AssociationExtraTargetName,
                                suggestedOperator: sourceHasProperty
                                    ? MetamodelAssociationOperators.COMPOSITION_SOURCE_NAVIGABLE_TARGET
                                    : MetamodelAssociationOperators.COMPOSITION_SOURCE
                            }
                        }
                    );
                }
                break;
        }

        if (sourceHasProperty && association.source?.class?.ref != undefined) {
            if (association.source.class.ref.$container !== association.$container) {
                accept(
                    "error",
                    `Cannot define a property on an association end for imported class '${association.source.name}'.`,
                    {
                        node: association.source,
                        property: "name"
                    }
                );
            }
        }
        if (targetHasProperty && association.target?.class?.ref != undefined) {
            if (association.target.class.ref.$container !== association.$container) {
                accept(
                    "error",
                    `Cannot define a property on an association end for imported class '${association.target.name}'.`,
                    {
                        node: association.target,
                        property: "name"
                    }
                );
            }
        }
    }

    /**
     * Validates multiplicity at an association end.
     * An association end with multiplicity must also have a property name.
     */
    private validateAssociationEndMultiplicity(
        end: AssociationEndType | undefined,
        association: AssociationType,
        accept: ValidationAcceptor
    ): void {
        if (!end?.multiplicity) {
            return;
        }

        if (!end.name) {
            accept(
                "error",
                "An association end cannot have a multiplicity without a property name. Add a property name or remove the multiplicity.",
                { node: association, property: association.source === end ? "source" : "target" }
            );
            return;
        }

        this.validateMultiplicityRange(end.multiplicity, association, accept);
    }

    /**
     * Validates composition multiplicity constraints.
     * The opposite side of a composition must have multiplicity 0..1 or nothing.
     * - If there is exactly one composition pointing to the class: 1 or nothing is valid
     * - If there are multiple compositions pointing to the class: must be 0..1 or nothing or ?
     */
    private validateCompositionMultiplicity(association: AssociationType, accept: ValidationAcceptor): void {
        const operator = association.operator;

        let compositeEnd: AssociationEndType | undefined;
        let oppositeEnd: AssociationEndType | undefined;

        switch (operator) {
            case MetamodelAssociationOperators.COMPOSITION_SOURCE:
            case MetamodelAssociationOperators.COMPOSITION_SOURCE_NAVIGABLE_TARGET:
                compositeEnd = association.source;
                oppositeEnd = association.target;
                break;

            case MetamodelAssociationOperators.COMPOSITION_TARGET:
            case MetamodelAssociationOperators.COMPOSITION_TARGET_NAVIGABLE_SOURCE:
                compositeEnd = association.target;
                oppositeEnd = association.source;
                break;

            default:
                return;
        }

        if (!oppositeEnd || !compositeEnd) {
            return;
        }

        const oppositeClass = oppositeEnd.class?.ref;
        if (!oppositeClass) {
            return;
        }

        const compositionCount = this.countCompositionsToClass(oppositeClass, association);

        this.validateOppositeCompositionMultiplicity(oppositeEnd, compositionCount, association, accept);
    }

    /**
     * Counts how many compositions point to a given class (considering class chain).
     */
    private countCompositionsToClass(targetClass: ClassType, currentAssociation: AssociationType): number {
        const metaModel = this.getContainingMetaModel(currentAssociation);
        if (!metaModel) {
            return 1;
        }

        const targetClassChain = new Set(resolveClassChain(targetClass, this.reflection));
        let count = 0;

        for (const element of metaModel.elements ?? []) {
            if (!this.reflection.isInstance(element, Association)) {
                continue;
            }

            const assoc = element;
            const operator = assoc.operator;

            switch (operator) {
                case MetamodelAssociationOperators.COMPOSITION_SOURCE:
                case MetamodelAssociationOperators.COMPOSITION_SOURCE_NAVIGABLE_TARGET: {
                    const targetCls = assoc.target?.class?.ref;
                    if (targetCls && targetClassChain.has(targetCls)) {
                        count++;
                    }
                    break;
                }

                case MetamodelAssociationOperators.COMPOSITION_TARGET:
                case MetamodelAssociationOperators.COMPOSITION_TARGET_NAVIGABLE_SOURCE: {
                    const sourceCls = assoc.source?.class?.ref;
                    if (sourceCls && targetClassChain.has(sourceCls)) {
                        count++;
                    }
                    break;
                }
            }
        }

        return count;
    }

    /**
     * Validates that the composite end has appropriate multiplicity.
     * - If exactly one composition: must be 1 or nothing
     * - If multiple compositions: must be 0..1 or nothing or ?
     */
    private validateOppositeCompositionMultiplicity(
        compositeEnd: AssociationEndType,
        compositionCount: number,
        association: AssociationType,
        accept: ValidationAcceptor
    ): void {
        const multiplicity = compositeEnd.multiplicity;

        if (!multiplicity) {
            return;
        }

        if (this.reflection.isInstance(multiplicity, SingleMultiplicity)) {
            const value = multiplicity.value;
            const numericValue = multiplicity.numericValue;

            if (compositionCount > 1) {
                if (value === "?" || numericValue === 0) {
                    return;
                }
                if (numericValue === 1) {
                    accept(
                        "error",
                        `Composition multiplicity must be optional (0..1, ?, or 0) when multiple compositions point to this class.`,
                        { node: association, property: "source" }
                    );
                }
                if (value === "*" || value === "+") {
                    accept(
                        "error",
                        `Composition multiplicity cannot be '${value}' - a class can only be contained by one parent.`,
                        { node: association, property: "source" }
                    );
                }
            } else {
                if (value === "*" || value === "+") {
                    accept(
                        "error",
                        `Composition multiplicity cannot be '${value}' - a class can only be contained by one parent.`,
                        { node: association, property: "source" }
                    );
                }
                if (numericValue !== undefined && numericValue > 1) {
                    accept(
                        "error",
                        `Composition multiplicity cannot exceed 1 - a class can only be contained by one parent.`,
                        { node: association, property: "source" }
                    );
                }
            }
        } else if (this.reflection.isInstance(multiplicity, RangeMultiplicity)) {
            const lower = multiplicity.lower;
            const upper = multiplicity.upper;
            const upperNumeric = multiplicity.upperNumeric;

            if (upper === "*") {
                accept(
                    "error",
                    `Composition multiplicity cannot have unbounded upper limit - a class can only be contained by one parent.`,
                    { node: association, property: "source" }
                );
            } else if (upperNumeric !== undefined && upperNumeric > 1) {
                accept(
                    "error",
                    `Composition multiplicity upper bound cannot exceed 1 - a class can only be contained by one parent.`,
                    { node: association, property: "source" }
                );
            }

            if (compositionCount > 1) {
                if (lower !== 0) {
                    accept(
                        "error",
                        `Composition multiplicity lower bound must be 0 when multiple compositions point to this class.`,
                        { node: association, property: "source" }
                    );
                }
            }
        }
    }

    /**
     * Gets the containing MetaModel for an AST node.
     */
    private getContainingMetaModel(node: AstNode): MetaModelType | undefined {
        let current: AstNode | undefined = node;
        while (current) {
            if (this.reflection.isInstance(current, MetaModel)) {
                return current;
            }
            current = current.$container;
        }
        return undefined;
    }

    /**
     * Gets a description of the node type for error messages.
     *
     * @param node The AST node to describe
     * @returns A human-readable description of the node type
     */
    private getNodeTypeDescription(node: AstNode): string {
        if (this.reflection.isInstance(node, Class)) {
            return "class";
        }
        if (this.reflection.isInstance(node, Enum)) {
            return "enum";
        }
        return "element";
    }

    /**
     * Gets the property to highlight for a given node.
     *
     * @param node The AST node
     * @returns The property name to highlight, or undefined
     */
    private getNameProperty(node: AstNode): string | undefined {
        if (this.reflection.isInstance(node, Class) || this.reflection.isInstance(node, Enum)) {
            return "name";
        }
        return undefined;
    }
}

/**
 * Collects all property names in the class hierarchy, including:
 * - Properties from the class itself
 * - Properties from parent classes
 * - Properties from association ends relevant to classes in the hierarchy
 *
 * @param classType The class to collect property names for
 * @param reflection The AST reflection utility
 * @returns Array of property names (may contain duplicates if there are conflicts)
 */
export function collectAllPropertyNames(classType: ClassType, reflection: AstReflection): string[] {
    const propertyNames: string[] = [];
    const classChain = resolveClassChain(classType, reflection);

    for (const cls of classChain) {
        for (const prop of cls.properties ?? []) {
            if (prop.name) {
                propertyNames.push(prop.name);
            }
        }

        const metaModel = getContainingMetaModel(cls, reflection);
        if (metaModel) {
            for (const element of metaModel.elements ?? []) {
                if (reflection.isInstance(element, Association)) {
                    const assoc = element;

                    const sourceClass = assoc.source?.class?.ref;
                    if (sourceClass === cls && assoc.source?.name) {
                        propertyNames.push(assoc.source.name);
                    }

                    const targetClass = assoc.target?.class?.ref;
                    if (targetClass === cls && assoc.target?.name) {
                        propertyNames.push(assoc.target.name);
                    }
                }
            }
        }
    }

    return propertyNames;
}

/**
 * Gets the containing MetaModel for an AST node.
 */
function getContainingMetaModel(node: AstNode, reflection: AstReflection): MetaModelType | undefined {
    let current: AstNode | undefined = node;
    while (current) {
        if (reflection.isInstance(current, MetaModel)) {
            return current;
        }
        current = current.$container;
    }
    return undefined;
}
