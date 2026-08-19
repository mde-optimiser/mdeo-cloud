import type { GNode, CreateEdgeResult } from "@mdeo/language-shared";
import { sharedImport } from "@mdeo/language-shared";
import { CreateEdgeOperation } from "@mdeo/protocol-common";
import type { AstNode } from "langium";
import {
    ClassExtension,
    ClassExtensions,
    type ClassType,
    type ClassExtensionType,
    type ClassExtensionsType
} from "../../../grammar/metamodelTypes.js";
import { MetamodelElementType } from "@mdeo/protocol-metamodel";
import { MetamodelBaseCreateEdgeOperationHandler } from "./metamodelBaseCreateEdgeOperationHandler.js";

const { injectable } = sharedImport("inversify");
const { GrammarUtils } = sharedImport("langium");

/**
 * Handles create-edge operations that produce inheritance (extends) edges in the metamodel diagram.
 * Adds a class extension entry to the source class, extending the target class.
 */
@injectable()
export class CreateInheritanceOperationHandler extends MetamodelBaseCreateEdgeOperationHandler {
    override readonly operationType = CreateEdgeOperation.KIND;
    override label = "Create inheritance edge";
    readonly elementTypeIds = [MetamodelElementType.EDGE_INHERITANCE];

    protected override async createEdge(
        operation: CreateEdgeOperation,
        sourceElement: GNode,
        targetElement: GNode
    ): Promise<CreateEdgeResult | undefined> {
        const sourceClass = this.resolveClass(sourceElement);
        if (sourceClass == undefined) {
            return undefined;
        }

        const targetClass = this.resolveClass(targetElement);
        if (targetClass == undefined) {
            return undefined;
        }

        return this.createExtensionEdge(sourceClass, targetClass);
    }

    /**
     * Creates an inheritance edge by adding a class extension to the source class.
     *
     * @param sourceClass The source class AST node
     * @param targetClass The target class AST node
     * @returns A CreateEdgeResult with the workspace edit to apply, or undefined on failure
     */
    private async createExtensionEdge(
        sourceClass: ClassType,
        targetClass: ClassType
    ): Promise<CreateEdgeResult | undefined> {
        const classCstNode = sourceClass.$cstNode;
        if (!classCstNode) {
            return undefined;
        }

        const existingExtensions = sourceClass.extensions?.extensions ?? [];

        const allExtensions: ClassExtensionType[] = [
            ...existingExtensions.map((ext) => ({
                $type: ClassExtension.name,
                class: { $refText: ext.class.$refText, ref: ext.class.ref }
            })),
            {
                $type: ClassExtension.name,
                class: { $refText: targetClass.name, ref: targetClass }
            }
        ];

        const classExtensions: ClassExtensionsType = {
            $type: ClassExtensions.name,
            extensions: allExtensions
        };

        const serialized = await this.serializeNode(classExtensions);

        let workspaceEdit;
        if (existingExtensions.length === 0) {
            const nameNode = GrammarUtils.findNodeForProperty(classCstNode, "name");
            if (!nameNode) {
                return undefined;
            }
            workspaceEdit = this.insertAfterCstNode(nameNode, ` ${serialized}`);
        } else {
            const extensionsNode = sourceClass.extensions;
            if (!extensionsNode?.$cstNode) {
                return undefined;
            }
            workspaceEdit = await this.replaceCstNode(extensionsNode.$cstNode, serialized);
        }

        // Build the new ClassExtension node used for id computation.
        const newExt: ClassExtensionType = {
            $type: ClassExtension.name,
            class: { $refText: targetClass.name, ref: targetClass }
        };

        // Determine the container for the InsertSpecification.
        // When no extensions block exists yet, temporarily attach one so that
        // computeInsertIds can locate the container while traversing the model tree.
        let container: ClassExtensionsType;
        if (sourceClass.extensions) {
            container = sourceClass.extensions;
        } else {
            container = { $type: ClassExtensions.name, extensions: [] } as ClassExtensionsType;
            (sourceClass as any).extensions = container;
        }

        return {
            edgeType: MetamodelElementType.EDGE_INHERITANCE,
            workspaceEdit,
            insertSpecification: {
                container: container as unknown as AstNode,
                property: "extensions",
                elements: [newExt as unknown as AstNode]
            },
            insertedElement: {
                element: newExt as unknown as AstNode,
                edge: {
                    type: MetamodelElementType.EDGE_INHERITANCE,
                    from: sourceClass as unknown as AstNode,
                    to: targetClass as unknown as AstNode
                }
            }
        };
    }
}
