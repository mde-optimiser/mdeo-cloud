import { MetadataManager, PlaceholderModelIdRegistry, sharedImport } from "@mdeo/language-shared";
import type { CreateNodeResult, GroupedToolboxItem, ToolboxItemProvider } from "@mdeo/language-shared";
import type { CreateNodeOperation, GhostElement } from "@eclipse-glsp/protocol";
import { Enum, EnumEntry, type EnumType } from "../../../grammar/metamodelTypes.js";
import type { WorkspaceEdit } from "vscode-languageserver-types";
import { MetamodelElementType } from "@mdeo/protocol-metamodel";
import type { MetamodelGModelFactory } from "../metamodelGModelFactory.js";
import type { MetamodelMetadataManager } from "../metamodelMetadataManager.js";
import type { NodeLayoutMetadata } from "@mdeo/protocol-common";
import { MetamodelBaseCreateNodeOperationHandler } from "./metamodelBaseCreateNodeOperationHandler.js";

const { injectable, inject } = sharedImport("inversify");
const { CreateNodeOperation: CreateNodeOperationKind, TriggerNodeCreationAction } =
    sharedImport("@eclipse-glsp/protocol");
const { GModelFactory } = sharedImport("@eclipse-glsp/server");

/**
 * Operation handler for creating new Enum nodes in the metamodel diagram.
 * Creates enums with an optional default entry.
 *
 * This handler also implements ToolboxItemProvider to supply palette items
 * with ghost element templates for visual preview during node creation.
 */
@injectable()
export class CreateEnumOperationHandler extends MetamodelBaseCreateNodeOperationHandler implements ToolboxItemProvider {
    readonly operationType = CreateNodeOperationKind.KIND;
    override readonly label = "Enum";
    readonly elementTypeIds = [MetamodelElementType.NODE_ENUM];

    @inject(GModelFactory)
    protected gModelFactory!: MetamodelGModelFactory;
    @inject(MetadataManager)
    protected metadataManager!: MetamodelMetadataManager;

    /**
     * Returns the trigger actions for creating enum nodes.
     *
     * @returns Array of trigger actions for enum creation
     */

    /**
     * Returns the trigger actions for creating enum nodes.
     *
     * @returns Array of trigger actions for enum creation
     */
    /**
     * Implements ToolboxItemProvider to supply grouped palette items.
     *
     * Returns two items: simple enum and enum with an entry.
     */
    async getToolboxItems(): Promise<GroupedToolboxItem[]> {
        return [
            {
                item: {
                    id: `create-enum-1`,
                    sortString: "E",
                    label: "Enum",
                    actions: [
                        TriggerNodeCreationAction.create(MetamodelElementType.NODE_ENUM, {
                            ghostElement: await this.createGhostElement(false)
                        })
                    ]
                },
                groupId: "create-group"
            },
            {
                item: {
                    id: `create-enum-2`,
                    sortString: "E",
                    label: "Enum with entry",
                    actions: [
                        TriggerNodeCreationAction.create(MetamodelElementType.NODE_ENUM, {
                            args: { includeEntry: true },
                            ghostElement: await this.createGhostElement(true)
                        })
                    ]
                },
                groupId: "create-group"
            }
        ];
    }

    /**
     * Creates a ghost element for visual preview during node creation.
     *
     * @param includeEntry Whether the ghost should include a default entry
     * @returns A Promise resolving to a `GhostElement` containing the template
     */
    protected async createGhostElement(includeEntry: boolean): Promise<GhostElement> {
        const enumAst = await this.createEnumAst(includeEntry);
        const idRegistry = new PlaceholderModelIdRegistry("__ghost_enum");
        const template = this.gModelFactory.createEnumNode(
            enumAst,
            idRegistry.getId(enumAst),
            this.metadataManager.getDefaultMetadata({ type: MetamodelElementType.NODE_ENUM }),
            idRegistry
        );
        return {
            template,
            dynamic: true
        };
    }

    /**
     * Creates a workspace edit for adding a new Enum node to the document.
     *
     * @param enumNode The `EnumType` AST node to insert
     * @returns A Promise resolving to the `WorkspaceEdit` that performs the insertion
     */
    protected async createEnumNode(enumNode: EnumType): Promise<WorkspaceEdit> {
        const serialized = await this.serializeNode(enumNode);
        const rootCstNode = this.modelState.sourceModel?.$cstNode;

        if (rootCstNode == undefined) {
            throw new Error("Root CST node is not available.");
        }

        const document = this.modelState.sourceModel?.$document;
        const isEmpty = document?.textDocument.getText().trim().length === 0;

        return await this.createInsertAfterNodeEdit(rootCstNode, serialized, !isEmpty);
    }

    /**
     * Creates an AST node for a new Enum and ensures a unique name.
     *
     * @param includeEntry Whether to include a default entry
     * @returns A Promise resolving to the created `EnumType` AST node
     */
    protected async createEnumAst(includeEntry: boolean): Promise<EnumType> {
        const name = await this.findUniqueName("NewEnum");
        const enumNode: EnumType = {
            $type: Enum.name,
            name,
            entries: []
        };

        if (includeEntry) {
            enumNode.entries.push({
                $type: EnumEntry.name,
                name: "VALUE"
            });
        }

        return enumNode;
    }

    /**
     * Handles the create node operation for enums.
     *
     * Computes a unique name, builds the AST, serializes it and returns
     * the corresponding workspace edit for insertion.
     *
     * @param operation The create node operation
     * @returns The created node id, type and workspace edit or `undefined`
     */
    override async createNode(operation: CreateNodeOperation): Promise<CreateNodeResult | undefined> {
        if (operation.elementTypeId !== MetamodelElementType.NODE_ENUM) {
            return undefined;
        }
        const includeEntry = operation.args?.includeEntry === true;
        const enumNode = await this.createEnumAst(includeEntry);
        const edit = await this.createEnumNode(enumNode);

        const metadata: NodeLayoutMetadata = {};
        if (operation.location) {
            metadata.position = operation.location;
        }

        return {
            workspaceEdit: this.addToActiveView(edit, enumNode),
            insertSpecifications: [
                {
                    container: this.modelState.sourceModel!,
                    property: "elements",
                    elements: [enumNode]
                }
            ],
            insertedElements: [
                {
                    element: enumNode,
                    node: { type: MetamodelElementType.NODE_ENUM, meta: metadata }
                }
            ]
        };
    }
}
