import type { DeleteElementOperation, GModelElement } from "@eclipse-glsp/server";
import {
    BaseDeleteElementOperationHandler,
    type DeleteOperationResult,
    GEdge,
    sharedImport
} from "@mdeo/language-shared";
import {
    Class,
    Association,
    ClassExtension,
    SingleMultiplicity,
    RangeMultiplicity,
    type ClassType,
    type AssociationType,
    type ClassExtensionType,
    type ClassExtensionsType,
    type AssociationEndType,
    type MultiplicityType,
    type EnumType,
    Enum,
    ClassExtensions
} from "../../../grammar/metamodelTypes.js";
import type { AstNode } from "langium";
import type { WorkspaceEdit } from "vscode-languageserver-types";
import type { ContextActionRequestContext } from "@mdeo/language-shared";
import type { ContextItem } from "@mdeo/protocol-common";
import { isImportedElement } from "./metamodelHandlerUtils.js";
import { GAssociationMultiplicityNode } from "../model/associationMultiplicityNode.js";

const { injectable } = sharedImport("inversify");
const { AstUtils } = sharedImport("langium");

/**
 * Operation handler for deleting nodes in the metamodel diagram.
 */
@injectable()
export class MetamodelDeleteNodeOperationHandler extends BaseDeleteElementOperationHandler {
    /**
     * Returns a delete context item only for local (non-imported) nodes.
     * Edges are excluded entirely to reduce context-menu clutter — associations and
     * inheritance edges can still be deleted via keyboard or toolbar actions.
     *
     * @param element The selected diagram element
     * @param context Additional request context
     * @returns A delete context item when appropriate, otherwise an empty array
     */
    override getContextItems(element: GModelElement, context: ContextActionRequestContext): ContextItem[] {
        if (element instanceof GEdge || isImportedElement(element) || element instanceof GAssociationMultiplicityNode) {
            return [];
        }

        if (isImportedElement(element)) {
            return [];
        }

        return super.getContextItems(element, context);
    }

    protected override async executeDelete(operation: DeleteElementOperation): Promise<DeleteOperationResult> {
        const elementsToDelete = this.convertIdsToElements(operation.elementIds);
        const allElementsToDelete = this.formTransitiveClosure(elementsToDelete);
        const withoutAutoDeletedExtensions = this.removeAutoDeletedExtensions(allElementsToDelete);
        const finalElementsToDelete = this.removeAutoDeletedMultiplicities(withoutAutoDeletedExtensions);
        const workspaceEdit = await this.createDeleteWorkspaceEdit(finalElementsToDelete);
        const deletedElements = this.collectDeletedGModelElements(finalElementsToDelete);

        return { workspaceEdit, deletedElements };
    }

    /**
     * Converts element IDs to their corresponding AST nodes.
     *
     * @param elementIds Array of element IDs to delete
     * @returns Array of AST nodes to delete
     */
    private convertIdsToElements(elementIds: string[]): AstNode[] {
        const elements: AstNode[] = [];
        for (const id of elementIds) {
            const element = this.modelState.index.find(id);
            if (element != undefined) {
                const astNode = this.index.getAstNode(element);
                if (astNode != undefined) {
                    elements.push(astNode);
                }
            }
        }
        return elements;
    }

    /**
     * Forms transitive closure by finding all connected elements that should be deleted.
     * Includes extension relations and associations connected to a deleted end.
     * Only considers elements within the same file.
     *
     * @param elements Initial elements to delete
     * @returns All elements to delete including transitively connected ones
     */
    private formTransitiveClosure(elements: AstNode[]): Set<AstNode> {
        const result = new Set<AstNode>(elements);
        const deletedClassOrEnumIds = new Set<string>();

        for (const element of elements) {
            if (this.reflection.isInstance(element, Class)) {
                const cls = element as ClassType;
                if (cls.name != undefined) {
                    deletedClassOrEnumIds.add(cls.name);
                }
            }
        }

        const sourceModel = this.modelState.sourceModel;
        if (sourceModel == undefined) {
            return result;
        }

        for (const node of AstUtils.streamAst(sourceModel)) {
            if (this.reflection.isInstance(node, ClassExtension)) {
                const extension = node as ClassExtensionType;
                const owner = this.getOwningClass(extension);
                const targetName = extension.class?.$refText;

                if (owner?.name != undefined && targetName != undefined) {
                    if (deletedClassOrEnumIds.has(owner.name) || deletedClassOrEnumIds.has(targetName)) {
                        result.add(extension);
                    }
                }
            }

            if (this.reflection.isInstance(node, Association)) {
                const association = node as AssociationType;
                const sourceClassName = association.source?.class?.$refText;
                const targetClassName = association.target?.class?.$refText;

                if (
                    (sourceClassName != undefined && deletedClassOrEnumIds.has(sourceClassName)) ||
                    (targetClassName != undefined && deletedClassOrEnumIds.has(targetClassName))
                ) {
                    result.add(association);
                }
            }
        }

        return result;
    }

    /**
     * Gets the owning class of a ClassExtension node.
     *
     * @param extension The ClassExtension node
     * @returns The owning Class node or undefined
     */
    private getOwningClass(extension: ClassExtensionType): ClassType | undefined {
        const classExtensions = extension.$container;
        if (classExtensions != undefined && classExtensions.$container != undefined) {
            const owner = classExtensions.$container;
            if (this.reflection.isInstance(owner, Class)) {
                return owner as ClassType;
            }
        }
        return undefined;
    }

    /**
     * Removes extension relations that will be automatically deleted when their owning class is deleted.
     *
     * @param elements All elements to delete
     * @returns Filtered set of elements without auto-deleted extensions
     */
    private removeAutoDeletedExtensions(elements: Set<AstNode>): Set<AstNode> {
        const result = new Set<AstNode>();
        const deletedClasses = new Set<ClassType>();

        for (const element of elements) {
            if (this.reflection.isInstance(element, Class)) {
                deletedClasses.add(element as ClassType);
            }
        }

        for (const element of elements) {
            if (this.reflection.isInstance(element, ClassExtension)) {
                const extension = element as ClassExtensionType;
                const owner = this.getOwningClass(extension);
                if (owner == undefined || !deletedClasses.has(owner)) {
                    result.add(element);
                }
            } else {
                result.add(element);
            }
        }

        return result;
    }

    /**
     * Removes multiplicities that will be automatically deleted when their owning association is deleted.
     *
     * @param elements All elements to delete
     * @returns Filtered set of elements without auto-deleted multiplicities
     */
    private removeAutoDeletedMultiplicities(elements: Set<AstNode>): Set<AstNode> {
        const result = new Set<AstNode>();
        const deletedAssociations = new Set<AssociationType>();

        for (const element of elements) {
            if (this.reflection.isInstance(element, Association)) {
                deletedAssociations.add(element as AssociationType);
            }
        }

        for (const element of elements) {
            if (
                this.reflection.isInstance(element, SingleMultiplicity) ||
                this.reflection.isInstance(element, RangeMultiplicity)
            ) {
                const multiplicity = element as MultiplicityType;
                const associationEnd = multiplicity.$container as AssociationEndType | undefined;
                if (associationEnd != undefined) {
                    const association = associationEnd.$container as AssociationType | undefined;
                    if (association == undefined || !deletedAssociations.has(association)) {
                        result.add(element);
                    }
                }
            } else {
                result.add(element);
            }
        }

        return result;
    }

    /**
     * Creates a workspace edit to delete all specified elements.
     *
     * @param elements Elements to delete
     * @returns Merged workspace edit for all deletions
     */
    private async createDeleteWorkspaceEdit(elements: Set<AstNode>): Promise<WorkspaceEdit> {
        const edits: WorkspaceEdit[] = [];

        const nodesToDelete: (ClassType | EnumType | AssociationType)[] = [];
        const extensions: ClassExtensionType[] = [];
        const multiplicities: MultiplicityType[] = [];

        for (const element of elements) {
            if (
                this.reflection.isInstance(element, Class) ||
                this.reflection.isInstance(element, Enum) ||
                this.reflection.isInstance(element, Association)
            ) {
                nodesToDelete.push(element);
            } else if (this.reflection.isInstance(element, ClassExtension)) {
                extensions.push(element);
            } else if (
                this.reflection.isInstance(element, SingleMultiplicity) ||
                this.reflection.isInstance(element, RangeMultiplicity)
            ) {
                multiplicities.push(element);
            }
        }

        for (const node of nodesToDelete) {
            if (node.$cstNode != undefined) {
                edits.push(this.deleteCstNode(node.$cstNode));
            }
        }

        edits.push(...(await this.createDeleteExtensionsEdits(extensions)));

        edits.push(...(await this.createDeleteMultiplicitiesEdits(multiplicities)));

        return this.mergeWorkspaceEdits(edits);
    }

    /**
     * Creates workspace edits for deleting class extensions.
     * Groups extensions by their owning class and reserializes the extends list.
     *
     * @param extensions Extensions to delete
     * @returns Array of workspace edits
     */
    private async createDeleteExtensionsEdits(extensions: ClassExtensionType[]): Promise<WorkspaceEdit[]> {
        const edits: WorkspaceEdit[] = [];
        const extensionsByClass = new Map<ClassType, ClassExtensionType[]>();

        for (const extension of extensions) {
            const owner = this.getOwningClass(extension);
            if (owner != undefined) {
                if (!extensionsByClass.has(owner)) {
                    extensionsByClass.set(owner, []);
                }
                extensionsByClass.get(owner)!.push(extension);
            }
        }

        for (const [cls, extensionsToDelete] of extensionsByClass) {
            const extensionsNode = cls.extensions;
            if (extensionsNode == undefined || extensionsNode.$cstNode == undefined) {
                continue;
            }

            const allExtensions = extensionsNode.extensions ?? [];
            const remainingExtensions = allExtensions.filter((ext) => !extensionsToDelete.includes(ext));

            if (remainingExtensions.length === 0) {
                edits.push(this.deleteCstNode(extensionsNode.$cstNode));
            } else {
                const newClassExtensions: ClassExtensionsType = {
                    $type: ClassExtensions.name,
                    extensions: remainingExtensions
                        .filter((ext) => ext.class != undefined)
                        .map((ext) => ({
                            $type: ClassExtension.name,
                            class: {
                                $refText: ext.class.$refText,
                                ref: ext.class.ref
                            }
                        }))
                };

                edits.push(await this.replaceCstNode(extensionsNode.$cstNode, newClassExtensions));
            }
        }

        return edits;
    }

    /**
     * Collects GModel elements corresponding to deleted AST nodes.
     *
     * @param elements AST nodes being deleted
     * @returns Array of GModel elements
     */
    private collectDeletedGModelElements(elements: Set<AstNode>): GModelElement[] {
        const gmodelElements: GModelElement[] = [];

        for (const element of elements) {
            if (
                this.reflection.isInstance(element, SingleMultiplicity) ||
                this.reflection.isInstance(element, RangeMultiplicity)
            ) {
                const multiplicity = element as MultiplicityType;
                const associationEnd = multiplicity.$container as AssociationEndType | undefined;
                if (associationEnd != undefined) {
                    const endElement = this.findGModelElementForAstNode(associationEnd);
                    if (endElement != undefined) {
                        const multiplicityElement = this.modelState.index.find(`${endElement.id}__multiplicity`);
                        if (multiplicityElement != undefined) {
                            gmodelElements.push(multiplicityElement);
                        }
                    }
                }
            } else {
                const gmodelElement = this.findGModelElementForAstNode(element);
                if (gmodelElement != undefined) {
                    gmodelElements.push(gmodelElement);
                }
            }
        }

        return gmodelElements;
    }

    /**
     * Finds the GModel element corresponding to an AST node.
     *
     * @param astNode The AST node to find
     * @returns The GModel element or undefined
     */
    private findGModelElementForAstNode(astNode: AstNode): GModelElement | undefined {
        const elementId = this.index.getElementId(astNode);
        if (elementId != undefined) {
            return this.modelState.index.find(elementId);
        }
        return undefined;
    }

    /**
     * Creates workspace edits for deleting multiplicities.
     *
     * @param multiplicities Multiplicity nodes being deleted
     * @returns Array of workspace edits
     */
    private async createDeleteMultiplicitiesEdits(multiplicities: MultiplicityType[]): Promise<WorkspaceEdit[]> {
        const edits: WorkspaceEdit[] = [];

        for (const multiplicity of multiplicities) {
            if (multiplicity.$cstNode != undefined) {
                edits.push(this.deleteCstNode(multiplicity.$cstNode));
            }
        }

        return edits;
    }
}
