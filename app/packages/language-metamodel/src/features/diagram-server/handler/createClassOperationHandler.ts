import { MetadataManager, PlaceholderModelIdRegistry, sharedImport } from "@mdeo/language-shared";
import type { CreateNodeResult, GroupedToolboxItem, ToolboxItemProvider } from "@mdeo/language-shared";
import type { CreateNodeOperation, GhostElement } from "@eclipse-glsp/protocol";
import type { ClassType } from "../../../grammar/metamodelTypes.js";
import { Class, MetamodelPrimitiveTypes, PrimitiveType, Property } from "../../../grammar/metamodelTypes.js";
import { MetamodelElementType } from "@mdeo/protocol-metamodel";
import type { MetamodelGModelFactory } from "../metamodelGModelFactory.js";
import type { MetamodelMetadataManager } from "../metamodelMetadataManager.js";
import type { WorkspaceEdit } from "vscode-languageserver-types";
import type { NodeLayoutMetadata } from "@mdeo/protocol-common";
import { MetamodelBaseCreateNodeOperationHandler } from "./metamodelBaseCreateNodeOperationHandler.js";

const { injectable, inject } = sharedImport("inversify");
const { CreateNodeOperation: CreateNodeOperationKind, TriggerNodeCreationAction } =
    sharedImport("@eclipse-glsp/protocol");
const { GModelFactory } = sharedImport("@eclipse-glsp/server");

/**
 * Operation handler for creating new nodes in the metamodel diagram.
 * Currently supports creating Class nodes.
 *
 * This handler also implements ToolboxItemProvider to supply palette items
 * with ghost element templates for visual preview during node creation.
 */
@injectable()
export class CreateClassOperationHandler
    extends MetamodelBaseCreateNodeOperationHandler
    implements ToolboxItemProvider
{
    readonly operationType = CreateNodeOperationKind.KIND;
    override readonly label = "Class";
    readonly elementTypeIds = [MetamodelElementType.NODE_CLASS];

    @inject(GModelFactory)
    protected gModelFactory!: MetamodelGModelFactory;
    @inject(MetadataManager)
    protected metadataManager!: MetamodelMetadataManager;

    override async createNode(operation: CreateNodeOperation): Promise<CreateNodeResult | undefined> {
        if (operation.elementTypeId === "node:class") {
            const node = await this.createClassAst(operation.args?.includeProperty === true);
            const edit = await this.createClassNode(node);

            const metadata: NodeLayoutMetadata = {};
            if (operation.location) {
                metadata.position = operation.location;
            }

            return {
                workspaceEdit: this.addToActiveView(edit, node),
                insertSpecifications: [
                    {
                        container: this.modelState.sourceModel!,
                        property: "elements",
                        elements: [node]
                    }
                ],
                insertedElements: [
                    {
                        element: node,
                        node: { type: MetamodelElementType.NODE_CLASS, meta: metadata }
                    }
                ]
            };
        }
        return undefined;
    }

    /**
     * Implements ToolboxItemProvider to supply grouped palette items.
     *
     * @returns Array of grouped toolbox items with ghost elements
     */
    async getToolboxItems(): Promise<GroupedToolboxItem[]> {
        return [
            {
                item: {
                    id: `create-class-1`,
                    sortString: "C",
                    label: "Class",
                    actions: [
                        TriggerNodeCreationAction.create(MetamodelElementType.NODE_CLASS, {
                            ghostElement: await this.createGhostElement(false)
                        })
                    ]
                },
                groupId: "create-group"
            },
            {
                item: {
                    id: `create-class-2`,
                    sortString: "C",
                    label: "Class with property",
                    actions: [
                        TriggerNodeCreationAction.create(MetamodelElementType.NODE_CLASS, {
                            args: { includeProperty: true },
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
     * @param includeProperty Whether to include a property in the preview
     * @returns Ghost element configuration with template schema
     */
    protected async createGhostElement(includeProperty: boolean): Promise<GhostElement> {
        const classAst = await this.createClassAst(includeProperty);
        const idRegistry = new PlaceholderModelIdRegistry("__ghost_class");
        const template = this.gModelFactory.createClassNode(
            classAst,
            idRegistry.getId(classAst),
            this.metadataManager.getDefaultMetadata({ type: MetamodelElementType.NODE_CLASS }),
            idRegistry
        );
        return {
            template,
            dynamic: true
        };
    }

    /**
     * Creates a workspace edit for adding a new Class node to the document.
     *
     * @param node The Class AST node to insert
     * @returns The workspace edit to perform the insertion
     */
    protected async createClassNode(node: ClassType): Promise<WorkspaceEdit> {
        const serialized = await this.serializeNode(node);

        const rootCstNode = this.modelState.sourceModel?.$cstNode;
        if (rootCstNode == undefined) {
            throw new Error("Root CST node is not available.");
        }

        const document = this.modelState.sourceModel?.$document;
        const isEmpty = document?.textDocument.getText().trim().length === 0;

        return await this.createInsertAfterNodeEdit(rootCstNode, serialized, !isEmpty);
    }

    /**
     * Creates an AST node for a new Class.
     *
     * @param includeProperty Whether to include an example property
     * @returns The created Class AST node
     */
    protected async createClassAst(includeProperty: boolean): Promise<ClassType> {
        const name = await this.findUniqueName("NewClass");
        const classNode: ClassType = {
            $type: Class.name,
            name,
            isAbstract: false,
            properties: [],
            extensions: undefined
        };

        if (includeProperty) {
            classNode.properties.push({
                $type: Property.name,
                name: "example",
                type: {
                    $type: PrimitiveType.name,
                    name: MetamodelPrimitiveTypes.STRING
                },
                multiplicity: undefined
            });
        }

        return classNode;
    }
}
