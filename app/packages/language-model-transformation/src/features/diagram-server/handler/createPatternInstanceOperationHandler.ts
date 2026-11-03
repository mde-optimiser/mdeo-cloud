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
    type ForMatchStatementType,
    type PatternModelScopeType
} from "../../../grammar/modelTransformationTypes.js";
import {
    ModelTransformationElementType,
    NodeCreationMode,
    PatternModifierKind
} from "@mdeo/protocol-model-transformation";
import type { ModelTransformationMetadataManager } from "../modelTransformationMetadataManager.js";
import type { ModelTransformationGModelFactory } from "../modelTransformationGModelFactory.js";
import {
    findContainingCondition,
    findContainingModelScope,
    patternModelScopes
} from "../modelTransformationPatternUtils.js";
import { containerBraces, visibleModelValues } from "../modelTransformationConditionMoves.js";
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
 * Operation handler for creating new pattern object instances in a model transformation diagram.
 * Supports all five creation modes: persist (no modifier), create, delete, require, forbid.
 *
 * For persist and delete modes, also provides "add instance" palette items that insert
 * a reference/delete for a previously declared instance.
 *
 * The toolbox may name an input model to create in (`args.model`, see
 * {@link ModelTransformationToolboxArgs}): a new instance then goes into a scope block
 * `in model { … }` of the target pattern — an existing one of that model, else a new one, inside
 * a new `forbid` / `require` block in those modes. An input model is read-only, so the create and
 * delete modes act like persist there, and "add instance" offers the objects of that model.
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
     *   for new instances, or args.instanceName / args.actionType for add-instance items, and
     *   args.model for an instance of an input model (only when that model is visible at the
     *   target pattern: a loop variable inside its loop).
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
        const model = this.targetModel(operation.args);
        const modifier = this.effectiveMode(operation.args?.modifier as NodeCreationMode | undefined, model);

        let workspaceEdit: WorkspaceEdit;
        let insertedNode: AstNode;
        let container: AstNode = pattern;

        if (model != undefined) {
            // the model must be visible here: a loop variable only inside its loop
            if (
                !visibleModelValues(pattern, this.reflection).some(
                    (value) => (value as { name?: string }).name === model
                )
            ) {
                return undefined;
            }
            const conditionKind = this.getConditionKind(modifier);
            if (actionType === "add-instance" && instanceName) {
                insertedNode = this.createReferenceAst(instanceName);
            } else {
                const className = operation.args?.className as string | undefined;
                if (!className) {
                    return undefined;
                }
                insertedNode = await this.createPatternObjectInstanceAst(className, PatternModifierKind.NONE);
            }
            if (conditionKind != undefined && actionType !== "add-instance") {
                workspaceEdit = await this.insertIntoNewCondition(pattern, conditionKind, insertedNode, model);
            } else {
                const scope = this.findModelScope(pattern, model);
                workspaceEdit = await this.insertIntoModel(pattern, scope, model, insertedNode);
                container = scope ?? pattern;
            }
        } else if (actionType === "add-instance" && instanceName) {
            if (modifier === NodeCreationMode.DELETE) {
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
            const conditionKind = this.getConditionKind(modifier);
            const modifierKind =
                conditionKind != undefined ? PatternModifierKind.NONE : this.modifierStringToKind(modifier);
            const newInstance = await this.createPatternObjectInstanceAst(className, modifierKind);
            workspaceEdit =
                conditionKind != undefined
                    ? await this.insertIntoNewCondition(pattern, conditionKind, newInstance)
                    : await this.insertIntoPattern(pattern, newInstance);
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
                    container,
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
        const model = this.targetModel(args);
        const mode = this.effectiveMode(args?.mode as NodeCreationMode | undefined, model);
        const classes = this.getAvailableClasses(mode !== NodeCreationMode.CREATE);
        const items: GroupedToolboxItem[] = [];
        const modelArgs: Args = model != undefined ? { model } : {};
        const modelIdPart = model != undefined ? `in-${model}-` : "";

        for (const classInfo of classes) {
            const ghostElement = await this.createNewInstanceGhostElement(
                classInfo.name,
                this.modifierStringToKind(mode),
                model
            );
            items.push({
                item: {
                    id: `create-pattern-instance-${mode}-${modelIdPart}${classInfo.name}`,
                    sortString: classInfo.name,
                    label: classInfo.name,
                    actions: [
                        TriggerNodeCreationAction.create(ModelTransformationElementType.NODE_PATTERN_INSTANCE, {
                            args: {
                                className: classInfo.name,
                                modifier: mode,
                                ...modelArgs
                            },
                            ghostElement
                        })
                    ]
                },
                groupId: "create-group"
            });
        }

        if (mode === NodeCreationMode.PERSIST || mode === NodeCreationMode.DELETE) {
            const existingInstances = this.getAllPatternObjectInstances(model);
            const isDeleteMode = mode === NodeCreationMode.DELETE;
            for (const instance of existingInstances) {
                const name = instance.name;
                if (name == undefined) {
                    continue;
                }

                const addModifierKind = isDeleteMode ? PatternModifierKind.DELETE : PatternModifierKind.NONE;
                const ghostElement = this.createAddInstanceGhostElement(name, addModifierKind, model);
                const label = isDeleteMode ? `delete ${name}` : name;
                items.push({
                    item: {
                        id: `add-instance-${mode}-${modelIdPart}${name}`,
                        sortString: name,
                        label,
                        actions: [
                            TriggerNodeCreationAction.create(ModelTransformationElementType.NODE_PATTERN_INSTANCE, {
                                args: {
                                    instanceName: name,
                                    modifier: mode,
                                    actionType: "add-instance",
                                    ...modelArgs
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
     * Returns the condition kind a creation mode stands for, or `undefined` for the modes
     * that create an element of the match pattern itself.
     *
     * @param mode The creation mode from the toolbox
     * @returns `"forbid"` / `"require"`, or `undefined`
     */
    private getConditionKind(mode: NodeCreationMode | undefined): "forbid" | "require" | undefined {
        if (mode === NodeCreationMode.FORBID || mode === NodeCreationMode.REQUIRE) {
            return mode;
        }
        return undefined;
    }

    /**
     * Inserts a new instance into a freshly created application condition block.
     *
     * Every node created in `forbid` / `require` mode starts out in a block of its own, so
     * that it rejects (or demands) a match independently of the other conditions. Blocks are
     * merged afterwards by moving one into the other's block, which is the only way to
     * express that two elements must be found *together*.
     *
     * @param pattern The pattern receiving the new block
     * @param conditionKind Whether a `forbid` or a `require` block is created
     * @param element The instance the block holds
     * @param model The input model the instance belongs to, if any: the block then holds a
     *   scope block of that model (`forbid { in donor { … } }`)
     * @returns A workspace edit inserting the new block
     * @throws {Error} If the pattern has no CST node
     */
    private async insertIntoNewCondition(
        pattern: PatternType,
        conditionKind: "forbid" | "require",
        element: AstNode,
        model?: string
    ): Promise<WorkspaceEdit> {
        const serialized = await this.serializeNode(element);
        const patternCst = pattern.$cstNode as CompositeCstNode | undefined;
        if (!patternCst) {
            throw new Error("Pattern has no CST node; cannot insert element.");
        }
        const content = patternCst.content;
        const openBrace = content[0]!;
        const closeBrace = content[content.length - 1]!;
        const hasContent = (pattern.elements?.length ?? 0) > 0;
        const body = model != undefined ? this.block(`in ${this.serializeName(model)}`, serialized) : serialized;
        return this.insertIntoScope(openBrace, closeBrace, hasContent, this.block(conditionKind, body));
    }

    /**
     * Inserts a new pattern element into a scope block of an input model: into the given
     * existing block, or into a new block `in model { … }` appended to the pattern.
     *
     * @param pattern The target pattern
     * @param scope The pattern's scope block of that model, if it has one
     * @param model The name of the model value
     * @param element The AST node to serialize and insert
     * @returns A workspace edit inserting the element
     * @throws {Error} If the pattern or the block is not backed by source text
     */
    private async insertIntoModel(
        pattern: PatternType,
        scope: PatternModelScopeType | undefined,
        model: string,
        element: AstNode
    ): Promise<WorkspaceEdit> {
        if (scope == undefined) {
            const serialized = await this.serializeNode(element);
            const braces = containerBraces(pattern);
            if (braces == undefined) {
                throw new Error("Pattern has no CST node; cannot insert element.");
            }
            const hasContent = (pattern.elements?.length ?? 0) > 0;
            return this.insertIntoScope(
                braces.open,
                braces.close,
                hasContent,
                this.block(`in ${this.serializeName(model)}`, serialized)
            );
        }
        const braces = containerBraces(scope);
        if (braces == undefined) {
            throw new Error("Scope block has no CST node; cannot insert element.");
        }
        const serialized = await this.serializeNode(element);
        return this.insertIntoScope(braces.open, braces.close, (scope.elements?.length ?? 0) > 0, serialized);
    }

    /**
     * Writes a block: its header and its body, indented, in braces.
     *
     * @param header The block's header, e.g. `forbid` or `in donor`
     * @param body The block's content
     * @returns The block's text
     */
    private block(header: string, body: string): string {
        const indented = body
            .split("\n")
            .map((line) => `    ${line}`)
            .join("\n");
        return `${header} {\n${indented}\n}`;
    }

    /**
     * Writes a name as an identifier of the language (in backticks when it has to be).
     *
     * @param name The name
     * @returns The identifier
     */
    private serializeName(name: string): string {
        return this.modelState.languageServices.AstSerializer.serializePrimitive({ value: name }, ID);
    }

    /**
     * Returns the scope block of a model declared directly in a pattern (not in one of its
     * condition blocks), the first one when there are several.
     *
     * @param pattern The pattern
     * @param model The name of the model value
     * @returns The block, or undefined when the pattern has none for that model
     */
    private findModelScope(pattern: PatternType, model: string): PatternModelScopeType | undefined {
        return patternModelScopes(pattern, this.reflection).find(
            (scope) => (scope.model?.ref?.name ?? scope.model?.$refText) === model
        );
    }

    /**
     * Returns the input model the toolbox creates in, from its arguments.
     *
     * @param args The toolbox or operation arguments
     * @returns The name of the model value, or undefined for the transformed model
     */
    private targetModel(args: Args | undefined): string | undefined {
        const model = args?.model;
        return typeof model === "string" && model.length > 0 ? model : undefined;
    }

    /**
     * Returns the creation mode that applies: an input model is read-only, so creating and
     * deleting act like persist there.
     *
     * @param mode The mode selected in the toolbox
     * @param model The input model created in, if any
     * @returns The mode
     */
    private effectiveMode(mode: NodeCreationMode | undefined, model: string | undefined): NodeCreationMode {
        if (model != undefined && (mode === NodeCreationMode.CREATE || mode === NodeCreationMode.DELETE)) {
            return NodeCreationMode.PERSIST;
        }
        return mode ?? NodeCreationMode.PERSIST;
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
     * Returns the PatternObjectInstances of a model that can be referenced from a match pattern.
     *
     * Instances declared in an application condition block are left out: their names belong to
     * the block's own graph and cannot be named from anywhere else, so referencing one would
     * only produce an unresolvable reference. So are the instances of other models: an object of
     * an input model is referenced only in a scope block of that model, an object of the
     * transformed model only outside scope blocks.
     *
     * @param model The input model, or undefined for the transformed model
     * @returns The referenceable `PatternObjectInstance` AST nodes of the entire source model,
     *   or an empty array when the source model is unavailable
     */
    private getAllPatternObjectInstances(model: string | undefined): PatternObjectInstanceType[] {
        const sourceModel = this.modelState.sourceModel as ModelTransformationType;
        if (!sourceModel) return [];

        const instances: PatternObjectInstanceType[] = [];
        for (const node of AstUtils.streamAllContents(sourceModel)) {
            if (
                this.reflection.isInstance(node, PatternObjectInstance) &&
                findContainingCondition(node, this.reflection) == undefined
            ) {
                const scope = findContainingModelScope(node, this.reflection);
                const instanceModel =
                    scope != undefined ? (scope.model?.ref?.name ?? scope.model?.$refText) : undefined;
                if (instanceModel === model) {
                    instances.push(node as PatternObjectInstanceType);
                }
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
     * @param model The input model the instance is created in, if any
     * @returns A `GhostElement` wrapping a preview `GPatternInstanceNode`
     */
    private async createNewInstanceGhostElement(
        className: string,
        modifier: PatternModifierKind,
        model?: string
    ): Promise<GhostElement> {
        const nodeId = "__ghost_pattern_instance";
        const instance = await this.createPatternObjectInstanceAst(className, modifier);
        const idRegistry = new PlaceholderModelIdRegistry(nodeId);
        const template = this.gModelFactory.createPatternInstanceNode(
            instance,
            idRegistry.getId(instance),
            this.metadataManager.getDefaultMetadata({ type: ModelTransformationElementType.NODE_PATTERN_INSTANCE }),
            idRegistry,
            undefined,
            this.ghostScope(model)
        );
        return { template, dynamic: true };
    }

    /**
     * Returns a stand-in for a scope block of the given model, so that a ghost element carries
     * the stereotype (`«in donor»`) of the block it will be created in.
     *
     * @param model The input model, if any
     * @returns The stand-in, or undefined for the transformed model
     */
    private ghostScope(model: string | undefined): PatternModelScopeType | undefined {
        if (model == undefined) {
            return undefined;
        }
        return {
            $type: "PatternModelScope",
            model: { $refText: model },
            elements: []
        } as unknown as PatternModelScopeType;
    }

    /**
     * Creates a ghost element for dragging an "add instance" reference/delete into a match node.
     *
     * @param instanceName The name of the existing instance to reference or delete
     * @param modifier The modifier kind (NONE for persist/reference, DELETE for delete mode)
     * @param model The input model the reference is created in, if any
     * @returns A `GhostElement` wrapping a preview `GPatternInstanceNode` without a type label
     */
    private createAddInstanceGhostElement(
        instanceName: string,
        modifier: PatternModifierKind,
        model?: string
    ): GhostElement {
        const nodeId = "__ghost_add_instance";
        const idRegistry = new PlaceholderModelIdRegistry(nodeId);
        const metadata = this.metadataManager.getDefaultMetadata({
            type: ModelTransformationElementType.NODE_PATTERN_INSTANCE
        });
        const template =
            modifier === PatternModifierKind.DELETE
                ? this.gModelFactory.createDeleteInstanceNode(instanceName, nodeId, metadata)
                : this.gModelFactory.createReferenceInstanceNode(
                      instanceName,
                      nodeId,
                      metadata,
                      idRegistry,
                      undefined,
                      this.ghostScope(model)
                  );
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
