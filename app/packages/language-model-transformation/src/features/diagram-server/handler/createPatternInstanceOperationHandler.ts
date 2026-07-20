import {
    BaseCreateNodeOperationHandler,
    MetadataManager,
    PlaceholderModelIdRegistry,
    resolveRelativePath,
    sharedImport,
    type CreateNodeResult,
    type GroupedToolboxItem,
    type ToolboxItemProvider
} from "@mdeo/language-shared";
import type { CreateNodeOperation, GhostElement, Args } from "@eclipse-glsp/protocol";
import type { AstNode, CompositeCstNode } from "langium";
import type { NodeLayoutMetadata } from "@mdeo/protocol-common";
import {
    PatternObjectInstance,
    PatternObjectInstanceReference,
    PatternObjectInstanceDelete,
    PatternPropertyAssignment,
    MatchStatement,
    IfMatchStatement,
    WhileMatchStatement,
    UntilMatchStatement,
    ForMatchStatement,
    PatternModifier,
    expressionTypes,
    type PatternObjectInstanceType,
    type PatternObjectInstanceReferenceType,
    type PatternObjectInstanceDeleteType,
    type PatternPropertyAssignmentType,
    type PatternType,
    type ModelTransformationType,
    type PatternModifierType,
    type MatchStatementType,
    type IfMatchStatementType,
    type WhileMatchStatementType,
    type UntilMatchStatementType,
    type ForMatchStatementType
} from "../../../grammar/modelTransformationTypes.js";
import { ModelTransformationElementType, PatternModifierKind } from "@mdeo/protocol-model-transformation";
import type { ModelTransformationMetadataManager } from "../modelTransformationMetadataManager.js";
import type { ModelTransformationGModelFactory } from "../modelTransformationGModelFactory.js";
import type { WorkspaceEdit } from "vscode-languageserver-types";
import {
    Class,
    Enum,
    EnumTypeReference,
    PrimitiveType,
    MetamodelPrimitiveTypes,
    isMultipleMultiplicity,
    isOptionalMultiplicity,
    type ClassType,
    type PropertyType,
    getExportedEntitiesFromMetamodelFile
} from "@mdeo/language-metamodel";
import type { MetamodelFileImportType } from "../../../grammar/modelTransformationTypes.js";
import type {
    BaseExpressionType,
    BooleanLiteralExpressionType,
    DoubleLiteralExpressionType,
    FloatLiteralExpressionType,
    IntLiteralExpressionType,
    ListExpressionType,
    LongLiteralExpressionType,
    MemberAccessExpressionType,
    StringLiteralExpressionType
} from "@mdeo/language-expression";
import { ID } from "@mdeo/language-common";

const { injectable, inject } = sharedImport("inversify");
const { CreateNodeOperation: CreateNodeOperationKind, TriggerNodeCreationAction } =
    sharedImport("@eclipse-glsp/protocol");
const { GModelFactory } = sharedImport("@eclipse-glsp/server");
const { AstUtils } = sharedImport("langium");

/**
 * The modifier arg value used for persist mode (no modifier keyword in grammar).
 */
const MODIFIER_PERSIST = "persist";

/**
 * Operation handler for creating new pattern object instances in a model transformation diagram.
 * Supports all five creation modes: persist (no modifier), create, delete, require, forbid.
 *
 * For persist and delete modes, also provides "add instance" palette items that insert
 * a reference/delete for a previously declared instance.
 */
@injectable()
export class CreatePatternInstanceOperationHandler
    extends BaseCreateNodeOperationHandler
    implements ToolboxItemProvider
{
    readonly operationType = CreateNodeOperationKind.KIND;
    override readonly label = "Pattern Instance";
    readonly elementTypeIds = [ModelTransformationElementType.NODE_PATTERN_INSTANCE];

    @inject(GModelFactory)
    protected gModelFactory!: ModelTransformationGModelFactory;

    @inject(MetadataManager)
    protected metadataManager!: ModelTransformationMetadataManager;

    /**
     * Creates a new pattern instance in the target match node.
     *
     * @param operation The create node operation, carrying args.className / args.modifier
     *   for new instances, or args.instanceName / args.actionType for add-instance items.
     */
    override async createNode(operation: CreateNodeOperation): Promise<CreateNodeResult | undefined> {
        if (operation.elementTypeId !== ModelTransformationElementType.NODE_PATTERN_INSTANCE) {
            return undefined;
        }

        const containerId = operation.containerId;
        if (!containerId) {
            return undefined;
        }

        const pattern = this.findPatternForContainer(containerId);
        if (!pattern) {
            return undefined;
        }

        const actionType = operation.args?.actionType as string | undefined;
        const instanceName = operation.args?.instanceName as string | undefined;
        const modifier = operation.args?.modifier as string | undefined;

        let workspaceEdit: WorkspaceEdit;
        let insertedNode: AstNode;

        if (actionType === "add-instance" && instanceName) {
            if (modifier === "delete") {
                const deleteNode = this.createDeleteAst(instanceName);
                workspaceEdit = await this.insertIntoPattern(pattern, deleteNode);
                insertedNode = deleteNode;
            } else {
                const refNode = this.createReferenceAst(instanceName);
                workspaceEdit = await this.insertIntoPattern(pattern, refNode);
                insertedNode = refNode;
            }
        } else {
            const className = operation.args?.className as string | undefined;
            if (!className) {
                return undefined;
            }
            const modifierKind = this.modifierStringToKind(modifier);
            const newInstance = await this.createPatternObjectInstanceAst(className, modifierKind);
            workspaceEdit = await this.insertIntoPattern(pattern, newInstance);
            insertedNode = newInstance;
        }

        const metadata: NodeLayoutMetadata = {};
        if (operation.location) {
            metadata.position = operation.location;
        }

        return {
            workspaceEdit,
            insertSpecifications: [
                {
                    container: pattern,
                    property: "elements",
                    elements: [insertedNode]
                }
            ],
            insertedElements: [
                {
                    element: insertedNode,
                    node: { type: ModelTransformationElementType.NODE_PATTERN_INSTANCE, meta: metadata }
                }
            ]
        };
    }

    /**
     * Provides toolbox items based on the current mode (from args.mode).
     *
     * - All modes: one "new instance" item per class; abstract classes are only included
     *   when the mode is not "create" (abstract classes cannot be instantiated).
     * - Persist and delete modes only: one "add instance" item per existing PatternObjectInstance.
     *
     * @param args Args from the toolbox request, including `mode` (NodeCreationMode value).
     */
    async getToolboxItems(args: Args | undefined): Promise<GroupedToolboxItem[]> {
        const mode = (args?.mode as string | undefined) ?? MODIFIER_PERSIST;
        const classes = this.getAvailableClasses(mode !== "create");
        const items: GroupedToolboxItem[] = [];

        for (const classInfo of classes) {
            const ghostElement = await this.createNewInstanceGhostElement(
                classInfo.name,
                this.modifierStringToKind(mode)
            );
            items.push({
                item: {
                    id: `create-pattern-instance-${mode}-${classInfo.name}`,
                    sortString: classInfo.name,
                    label: classInfo.name,
                    actions: [
                        TriggerNodeCreationAction.create(ModelTransformationElementType.NODE_PATTERN_INSTANCE, {
                            args: {
                                className: classInfo.name,
                                modifier: mode
                            },
                            ghostElement
                        })
                    ]
                },
                groupId: "create-group"
            });
        }

        if (mode === MODIFIER_PERSIST || mode === "delete") {
            const existingInstances = this.getAllPatternObjectInstances();
            const isDeleteMode = mode === "delete";
            for (const instance of existingInstances) {
                const name = instance.name;
                if (name == undefined) {
                    continue;
                }

                const addModifierKind = isDeleteMode ? PatternModifierKind.DELETE : PatternModifierKind.NONE;
                const ghostElement = this.createAddInstanceGhostElement(name, addModifierKind);
                const label = isDeleteMode ? `delete ${name}` : name;
                items.push({
                    item: {
                        id: `add-instance-${mode}-${name}`,
                        sortString: name,
                        label,
                        actions: [
                            TriggerNodeCreationAction.create(ModelTransformationElementType.NODE_PATTERN_INSTANCE, {
                                args: {
                                    instanceName: name,
                                    modifier: mode,
                                    actionType: "add-instance"
                                },
                                ghostElement
                            })
                        ]
                    },
                    groupId: "add-group"
                });
            }
        }

        return items;
    }

    /**
     * Creates a PatternObjectInstance AST node for a new instance.
     *
     * @param className The name of the class/type of the instance
     * @param modifier The modifier kind (create, delete, require, forbid, or none) that determines the instance's modifier and default properties
     * @returns A fully constructed `PatternObjectInstanceType` AST node with an unresolved class reference and default property values
     */
    private async createPatternObjectInstanceAst(
        className: string,
        modifier: PatternModifierKind
    ): Promise<PatternObjectInstanceType> {
        const baseName =
            modifier === PatternModifierKind.CREATE
                ? `new${className}`
                : `${className.charAt(0).toLowerCase()}${className.slice(1)}`;
        const name = await this.findUniqueName(baseName);

        const modifierNode: PatternModifierType | undefined =
            modifier !== PatternModifierKind.NONE
                ? {
                      $type: PatternModifier.name,
                      modifier: modifier as string
                  }
                : undefined;

        const classType = this.getAvailableClasses(true).find((c) => c.name === className)?.classType;
        const properties =
            modifier === PatternModifierKind.CREATE && classType !== undefined
                ? this.createDefaultPatternProperties(classType)
                : [];

        const instance: PatternObjectInstanceType = {
            $type: PatternObjectInstance.name,
            modifier: modifierNode,
            name,
            class: { $refText: className, ref: undefined },
            properties
        };

        return instance;
    }

    /**
     * Creates default property assignments for a new pattern instance based on the given class type.
     *
     * @param classType The class type of the new instance, used to determine which properties to include and their default values
     * @returns An array of `PatternPropertyAssignmentType` nodes for all non-optional properties of the class and its superclasses, with default values where possible
     */
    private createDefaultPatternProperties(classType: ClassType): PatternPropertyAssignmentType[] {
        const reflection = this.modelState.languageServices.shared.AstReflection;
        const properties: PatternPropertyAssignmentType[] = [];
        const allProperties = this.getAllClassProperties(classType);

        for (const prop of allProperties) {
            if (isOptionalMultiplicity(prop.multiplicity, reflection)) {
                continue;
            }

            if (isMultipleMultiplicity(prop.multiplicity, reflection)) {
                const expression: ListExpressionType = {
                    $type: expressionTypes.listExpressionType.name,
                    $cstNode: {
                        text: "[]"
                    } as any,
                    elements: []
                };
                properties.push({
                    $type: PatternPropertyAssignment.name,
                    name: { $refText: prop.name ?? "", ref: prop },
                    operator: "=",
                    value: expression
                });
                continue;
            } else {
                const defaultValue = this.getDefaultExpressionForType(prop);
                if (defaultValue != undefined) {
                    properties.push({
                        $type: PatternPropertyAssignment.name,
                        name: { $refText: prop.name ?? "", ref: prop },
                        operator: "=",
                        value: defaultValue
                    });
                }
            }
        }

        return properties;
    }

    /**
     * Retrieves all properties of the given class type, including inherited properties from superclasses.
     *
     * @param classType The class type to retrieve properties for
     * @returns An array of `PropertyType` objects representing all properties of the class and its superclasses
     */
    private getAllClassProperties(classType: ClassType): PropertyType[] {
        const properties: PropertyType[] = [];
        const visited = new Set<ClassType>();

        const collectProperties = (cls: ClassType): void => {
            if (visited.has(cls)) {
                return;
            }
            visited.add(cls);
            properties.push(...(cls.properties ?? []));
            for (const extension of cls.extensions?.extensions ?? []) {
                const parentClass = extension.class?.ref;
                if (parentClass && this.reflection.isInstance(parentClass, Class)) {
                    collectProperties(parentClass as ClassType);
                }
            }
        };

        collectProperties(classType);
        return properties;
    }

    /**
     * Retrieves the default expression for a given property type.
     *
     * @param property The property to retrieve the default expression for
     * @returns A `BaseExpressionType` representing the default value, or `undefined` if no default is available
     */
    private getDefaultExpressionForType(property: PropertyType): BaseExpressionType | undefined {
        const type = property.type;

        if (this.reflection.isInstance(type, PrimitiveType)) {
            switch (type.name) {
                case MetamodelPrimitiveTypes.INT:
                    return {
                        $type: expressionTypes.intLiteralExpressionType.name,
                        value: "0",
                        $cstNode: { text: "0" } as any
                    } as IntLiteralExpressionType;
                case MetamodelPrimitiveTypes.LONG:
                    return {
                        $type: expressionTypes.longLiteralExpressionType.name,
                        value: "0",
                        $cstNode: { text: "0" } as any
                    } as LongLiteralExpressionType;
                case MetamodelPrimitiveTypes.FLOAT:
                    return {
                        $type: expressionTypes.floatLiteralExpressionType.name,
                        value: "0",
                        $cstNode: { text: "0" } as any
                    } as FloatLiteralExpressionType;
                case MetamodelPrimitiveTypes.DOUBLE:
                    return {
                        $type: expressionTypes.doubleLiteralExpressionType.name,
                        value: "0",
                        $cstNode: { text: "0" } as any
                    } as DoubleLiteralExpressionType;
                case MetamodelPrimitiveTypes.BOOLEAN:
                    return {
                        $type: expressionTypes.booleanLiteralExpressionType.name,
                        value: false,
                        $cstNode: { text: "false" } as any
                    } as BooleanLiteralExpressionType;
                case MetamodelPrimitiveTypes.STRING:
                    return {
                        $type: expressionTypes.stringLiteralExpressionType.name,
                        value: "",
                        $cstNode: { text: '""' } as any
                    } as StringLiteralExpressionType;
            }
        } else if (this.reflection.isInstance(type, EnumTypeReference)) {
            const enumType = type.enum?.ref;
            if (!this.reflection.isInstance(enumType, Enum)) {
                return undefined;
            }
            const firstEntry = enumType.entries?.[0]?.name;
            if (firstEntry === undefined) {
                return undefined;
            }
            const astSerializer = this.modelState.languageServices.AstSerializer;
            return {
                $type: expressionTypes.memberAccessExpressionType.name,
                expression: { $type: expressionTypes.identifierExpressionType.name, name: enumType.name ?? "" },
                member: firstEntry,
                isNullChaining: false,
                $cstNode: {
                    text: `${astSerializer.serializePrimitive({ value: enumType.name }, ID)}.${astSerializer.serializePrimitive({ value: firstEntry }, ID)}`
                } as any
            } as MemberAccessExpressionType;
        }

        return undefined;
    }

    /**
     * Creates a PatternObjectInstanceReference AST node referencing an existing instance.
     *
     * @param instanceName The name of the instance to reference
     * @returns A new `PatternObjectInstanceReference` AST node with an unresolved ref text
     */
    private createReferenceAst(instanceName: string): PatternObjectInstanceReferenceType {
        return {
            $type: PatternObjectInstanceReference.name,
            instance: { $refText: instanceName, ref: undefined },
            properties: []
        };
    }

    /**
     * Creates a PatternObjectInstanceDelete AST node for deleting an existing instance.
     *
     * @param instanceName The name of the instance to delete
     * @returns A new `PatternObjectInstanceDelete` AST node with an unresolved ref text
     */
    private createDeleteAst(instanceName: string): PatternObjectInstanceDeleteType {
        return {
            $type: PatternObjectInstanceDelete.name,
            instance: { $refText: instanceName, ref: undefined }
        };
    }

    /**
     * Inserts a new pattern element AST node into the given pattern using {@link insertIntoScope}.
     * The serialized element is placed just before the closing `}` of the pattern block.
     * When the pattern already contains elements, a blank line is added before the content.
     *
     * @param pattern The target pattern whose element list receives the new node
     * @param element The AST node to serialize and insert
     * @returns A workspace edit that inserts the serialized node into the source document
     * @throws {Error} If the pattern has no CST node and is therefore not backed by source text
     */
    private async insertIntoPattern(pattern: PatternType, element: AstNode): Promise<WorkspaceEdit> {
        const serialized = await this.serializeNode(element);
        const patternCst = pattern.$cstNode as CompositeCstNode | undefined;
        if (!patternCst) {
            throw new Error("Pattern has no CST node; cannot insert element.");
        }
        const content = patternCst.content;
        const openBrace = content[0]!;
        const closeBrace = content[content.length - 1]!;
        const hasContent = (pattern.elements?.length ?? 0) > 0;
        return this.insertIntoScope(openBrace, closeBrace, hasContent, serialized);
    }

    /**
     * Finds the Pattern AST node for the match node identified by containerId.
     *
     * The containerId is the ID of the GMatchNode as generated by the GModel factory:
     * - Simple MatchStatement: id = "MatchStatement_${name}" (e.g. "MatchStatement_0")
     * - Derived match nodes (IfMatch, WhileMatch, …): id = "Pattern_${name}" (e.g. "Pattern_0_0")
     *
     * @param containerId The GModel ID of the target match node
     * @returns The matching `PatternType` AST node, or `undefined` if not found
     */
    private findPatternForContainer(containerId: string): PatternType | undefined {
        const sourceModel = this.modelState.sourceModel as ModelTransformationType;
        if (!sourceModel) {
            return undefined;
        }

        for (const node of AstUtils.streamAllContents(sourceModel)) {
            const pattern = this.tryGetPatternFromNode(node, containerId);
            if (pattern !== undefined) {
                return pattern;
            }
        }

        return undefined;
    }

    /**
     * Tries to extract the Pattern from a given AST node if the node is a
     * match-type statement whose generated match node ID equals containerId.
     *
     * @param node The AST node to inspect
     * @param containerId The GModel ID being searched for
     * @returns The `PatternType` if the node is a matching statement, otherwise `undefined`
     */
    private tryGetPatternFromNode(node: AstNode, containerId: string): PatternType | undefined {
        const elementId = this.index.getElementId(node);

        if (this.reflection.isInstance(node, MatchStatement) && elementId !== undefined) {
            if (elementId === containerId) {
                return (node as MatchStatementType).pattern;
            }
        }

        if (this.reflection.isInstance(node, IfMatchStatement)) {
            const pattern = (node as IfMatchStatementType).ifBlock?.pattern;
            if (pattern != undefined && this.index.getElementId(pattern) === containerId) {
                return pattern;
            }
        }

        if (this.reflection.isInstance(node, WhileMatchStatement)) {
            const pattern = (node as WhileMatchStatementType).pattern;
            if (pattern != undefined && this.index.getElementId(pattern) === containerId) {
                return pattern;
            }
        }

        if (this.reflection.isInstance(node, UntilMatchStatement)) {
            const pattern = (node as UntilMatchStatementType).pattern;
            if (pattern != undefined && this.index.getElementId(pattern) === containerId) {
                return pattern;
            }
        }

        if (this.reflection.isInstance(node, ForMatchStatement)) {
            const pattern = (node as ForMatchStatementType).pattern;
            if (pattern != undefined && this.index.getElementId(pattern) === containerId) {
                return pattern;
            }
        }

        return undefined;
    }

    /**
     * Returns the classes exported by the imported metamodel.
     * Reads the metamodel file resolved from the transformation's import statement.
     *
     * @param includeAbstract When `false`, abstract classes are omitted (they cannot be
     *   instantiated, e.g. in create mode); when `true`, all exported classes are returned.
     * @returns An array of `{ name, classType }` entries for each matching exported class,
     *   or an empty array when the import or its document is unavailable
     */
    private getAvailableClasses(includeAbstract: boolean): Array<{ name: string; classType: ClassType }> {
        const sourceModel = this.modelState.sourceModel as ModelTransformationType;
        const sourceModelDoc = sourceModel?.$document;
        const importedFile = (sourceModel?.import as MetamodelFileImportType | undefined)?.file;

        if (importedFile == undefined || sourceModelDoc == undefined) {
            return [];
        }

        const metamodelUri = resolveRelativePath(sourceModelDoc, importedFile);
        const documents = this.modelState.languageServices.shared.workspace.LangiumDocuments;
        const doc = documents.getDocument(metamodelUri);

        if (doc == undefined) {
            return [];
        }

        const exports = getExportedEntitiesFromMetamodelFile(doc, documents);
        const classes: Array<{ name: string; classType: ClassType }> = [];

        for (const classType of exports.classes) {
            if (!this.reflection.isInstance(classType, Class)) {
                continue;
            }
            if (!includeAbstract && (classType as ClassType).isAbstract) {
                continue;
            }
            classes.push({ name: (classType as ClassType).name ?? "Unknown", classType: classType as ClassType });
        }

        return classes;
    }

    /**
     * Returns all PatternObjectInstances declared anywhere in the transformation.
     * Used to populate "add instance" toolbox items.
     *
     * @returns All `PatternObjectInstance` AST nodes found in the entire source model,
     *   or an empty array when the source model is unavailable
     */
    private getAllPatternObjectInstances(): PatternObjectInstanceType[] {
        const sourceModel = this.modelState.sourceModel as ModelTransformationType;
        if (!sourceModel) return [];

        const instances: PatternObjectInstanceType[] = [];
        for (const node of AstUtils.streamAllContents(sourceModel)) {
            if (this.reflection.isInstance(node, PatternObjectInstance)) {
                instances.push(node as PatternObjectInstanceType);
            }
        }
        return instances;
    }

    /**
     * Creates a ghost element for dragging a new instance into a match node.
     * For CREATE mode, the ghost includes the default properties of the class.
     *
     * @param className The class name used to derive the default instance name (`new<ClassName>`)
     * @param modifier The modifier kind applied to the new instance
     * @returns A `GhostElement` wrapping a preview `GPatternInstanceNode`
     */
    private async createNewInstanceGhostElement(
        className: string,
        modifier: PatternModifierKind
    ): Promise<GhostElement> {
        const nodeId = "__ghost_pattern_instance";
        const instance = await this.createPatternObjectInstanceAst(className, modifier);
        const idRegistry = new PlaceholderModelIdRegistry(nodeId);
        const template = this.gModelFactory.createPatternInstanceNode(
            instance,
            idRegistry.getId(instance),
            this.metadataManager.getDefaultMetadata({ type: ModelTransformationElementType.NODE_PATTERN_INSTANCE }),
            idRegistry
        );
        return { template, dynamic: true };
    }

    /**
     * Creates a ghost element for dragging an "add instance" reference/delete into a match node.
     *
     * @param instanceName The name of the existing instance to reference or delete
     * @param modifier The modifier kind (NONE for persist/reference, DELETE for delete mode)
     * @returns A `GhostElement` wrapping a preview `GPatternInstanceNode` without a type label
     */
    private createAddInstanceGhostElement(instanceName: string, modifier: PatternModifierKind): GhostElement {
        const nodeId = "__ghost_add_instance";
        const idRegistry = new PlaceholderModelIdRegistry(nodeId);
        const metadata = this.metadataManager.getDefaultMetadata({
            type: ModelTransformationElementType.NODE_PATTERN_INSTANCE
        });
        const template =
            modifier === PatternModifierKind.DELETE
                ? this.gModelFactory.createDeleteInstanceNode(instanceName, nodeId, metadata)
                : this.gModelFactory.createReferenceInstanceNode(instanceName, nodeId, metadata, idRegistry);
        return { template, dynamic: true };
    }

    /**
     * Converts a modifier string (as passed in args) to a `PatternModifierKind` enum value.
     * Unknown or missing modifier strings default to `PatternModifierKind.NONE`.
     *
     * @param modifier The raw modifier string from operation args (e.g. `"create"`, `"delete"`)
     * @returns The corresponding `PatternModifierKind` value
     */
    private modifierStringToKind(modifier: string | undefined): PatternModifierKind {
        switch (modifier) {
            case "create":
                return PatternModifierKind.CREATE;
            case "delete":
                return PatternModifierKind.DELETE;
            case "require":
                return PatternModifierKind.REQUIRE;
            case "forbid":
                return PatternModifierKind.FORBID;
            default:
                return PatternModifierKind.NONE;
        }
    }
}
