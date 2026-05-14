import { BaseApplyLabelEditOperationHandler, parseIdentifier, sharedImport } from "@mdeo/language-shared";
import {
    Class,
    Property,
    Enum,
    EnumEntry,
    MetamodelPrimitiveTypes,
    type PropertyType,
    type PropertyTypeValueType,
    AssociationEnd,
    type AssociationEndType,
    PrimitiveType,
    EnumTypeReference,
    RangeMultiplicity,
    type MultiplicityType,
    type RangeMultiplicityType,
    type SingleMultiplicityType
} from "../../../grammar/metamodelTypes.js";
import type { AstNode } from "langium";
import type { WorkspaceEdit } from "vscode-languageserver-types";
import type { ApplyLabelEditOperation } from "@eclipse-glsp/server";
import { parsePropertyLabel } from "../metamodelLabelEditValidator.js";
import { parseMultiplicity } from "./metamodelHandlerUtils.js";

const { injectable } = sharedImport("inversify");
const { GrammarUtils } = sharedImport("langium");

/**
 * Handler for applying label edit operations from the client.
 */
@injectable()
export class MetamodelApplyLabelEditOperationHandler extends BaseApplyLabelEditOperationHandler {
    /**
     * Creates a workspace edit for a label edit operation.
     *
     * @param node The AST node being edited
     * @param operation The label edit operation
     * @returns The workspace edit or undefined
     */
    override async createLabelEdit(
        node: AstNode,
        operation: ApplyLabelEditOperation
    ): Promise<WorkspaceEdit | undefined> {
        if (this.reflection.isInstance(node, Class)) {
            return this.createNameEdit(node, operation.text);
        } else if (this.reflection.isInstance(node, Enum)) {
            return this.createNameEdit(node, operation.text);
        } else if (this.reflection.isInstance(node, EnumEntry)) {
            if (operation.text.trim().length === 0) {
                if (node.$cstNode == undefined) {
                    return undefined;
                }
                return this.deleteCstNode(node.$cstNode);
            }
            return this.createNameEdit(node, operation.text);
        } else if (this.reflection.isInstance(node, Property)) {
            return await this.createRenamePropertyEdit(node, operation.text);
        } else if (this.reflection.isInstance(node, AssociationEnd)) {
            return await this.createRenameAssociationEndEdit(node, operation);
        }
        return undefined;
    }

    /**
     * Creates a workspace edit for renaming an element's name property.
     *
     * @param node the node with a name property
     * @param text the new name text
     * @returns a workspace edit for renaming, or undefined if no name node found
     */
    private createNameEdit(node: AstNode, text: string): WorkspaceEdit | undefined {
        const nameNode = GrammarUtils.findNodeForProperty(node.$cstNode, "name");
        if (nameNode != undefined) {
            return this.createRenameWorkspaceEdit(nameNode, text.trim());
        }
        return undefined;
    }

    /**
     * Creates a workspace edit for renaming a property based on the edited label.
     *
     * @param node the property node
     * @param text the edited label text
     * @returns a workspace edit for renaming the property, or undefined if parsing fails
     */
    private async createRenamePropertyEdit(node: PropertyType, text: string): Promise<WorkspaceEdit | undefined> {
        if (text.trim().length === 0) {
            if (node.$cstNode == undefined) {
                return undefined;
            }
            return this.deleteCstNode(node.$cstNode);
        }

        const parsed = parsePropertyLabel(text);
        if (parsed == undefined) {
            return undefined;
        }

        const edits: WorkspaceEdit[] = [];

        const nameNode = GrammarUtils.findNodeForProperty(node.$cstNode, "name");
        if (nameNode != undefined) {
            const renameEdit = this.createRenameWorkspaceEdit(nameNode, parseIdentifier(parsed.identifier));
            if (renameEdit != undefined) {
                edits.push(renameEdit);
            }
        }

        const typeEdit = await this.createPropertyTypeEdit(node, parsed.type);
        if (typeEdit != undefined) {
            edits.push(typeEdit);
        }

        if (parsed.multiplicity != undefined) {
            const multiplicityEdit = await this.createMultiplicityEdit(node, parsed.multiplicity);
            if (multiplicityEdit != undefined) {
                edits.push(multiplicityEdit);
            }
        } else if (node.multiplicity != undefined) {
            const multiplicityNode = GrammarUtils.findNodeForProperty(node.$cstNode, "multiplicity");
            if (multiplicityNode != undefined) {
                edits.push(this.deleteCstNode(multiplicityNode));
            }
        }

        return this.mergeWorkspaceEdits(edits);
    }

    /**
     * Creates a workspace edit for changing the property type.
     * Handles both primitive types and enum type references.
     *
     * @param node the property node
     * @param typeName the new type name
     * @returns a workspace edit for changing the type
     */
    private async createPropertyTypeEdit(node: PropertyType, typeName: string): Promise<WorkspaceEdit | undefined> {
        const typeNode = GrammarUtils.findNodeForProperty(node.$cstNode, "type");
        if (typeNode == undefined) {
            return undefined;
        }

        const newTypeAst = this.createPropertyTypeAst(typeName);
        return await this.replaceCstNode(typeNode, newTypeAst);
    }

    /**
     * Creates an AST node for a property type value.
     * Returns a PrimitiveType for primitive type names, or an EnumTypeReference otherwise.
     *
     * @param typeName the type name string
     * @returns the property type value AST node
     */
    private createPropertyTypeAst(typeName: string): PropertyTypeValueType {
        const primitiveTypes = Object.values(MetamodelPrimitiveTypes) as string[];
        if (primitiveTypes.includes(typeName)) {
            return {
                $type: PrimitiveType.name,
                name: typeName as MetamodelPrimitiveTypes
            };
        }
        return {
            $type: EnumTypeReference.name,
            enum: { $refText: typeName }
        } as PropertyTypeValueType;
    }

    /**
     * Creates a workspace edit for changing the multiplicity.
     *
     * @param node the property node
     * @param multiplicityText the new multiplicity text
     * @returns a workspace edit for changing the multiplicity
     */
    private async createMultiplicityEdit(
        node: PropertyType,
        multiplicityText: string
    ): Promise<WorkspaceEdit | undefined> {
        const multiplicityAst = parseMultiplicity(multiplicityText);
        if (multiplicityAst == undefined) {
            throw new Error("Invalid multiplicity format.");
        }

        if (node.multiplicity != undefined) {
            const multiplicityNode = GrammarUtils.findNodeForProperty(node.$cstNode, "multiplicity");
            if (multiplicityNode == undefined) {
                throw new Error("Multiplicity CST node not found.");
            }
            return await this.replaceCstNode(multiplicityNode, multiplicityAst);
        } else {
            const typeNode = GrammarUtils.findNodeForProperty(node.$cstNode, "type");
            if (typeNode != undefined) {
                const serializedMultiplicity = await this.serializeNode(multiplicityAst);
                const newTypeText = typeNode.text + serializedMultiplicity;
                return await this.replaceCstNode(typeNode, newTypeText);
            }
        }
        return undefined;
    }

    /**
     * Creates a workspace edit for renaming an association end property or multiplicity.
     *
     * @param node The association node
     * @param operation The apply label edit operation
     * @returns A workspace edit for renaming the association end, or undefined if parsing fails
     */
    private async createRenameAssociationEndEdit(
        node: AssociationEndType,
        operation: ApplyLabelEditOperation
    ): Promise<WorkspaceEdit | undefined> {
        const labelId = operation.labelId;
        const text = operation.text.trim();

        if (labelId.endsWith("__property-label")) {
            const propertyNode = GrammarUtils.findNodeForProperty(node.$cstNode, "name");
            if (propertyNode == undefined) {
                throw new Error("Property CST node not found.");
            }
            return this.createRenameWorkspaceEdit(propertyNode, parseIdentifier(text));
        } else if (labelId.endsWith("__multiplicity-label")) {
            return await this.updateAssociationMultiplicity(node, text);
        } else {
            throw new Error(`Unknown label ID type: ${labelId}`);
        }
    }

    /**
     * Updates the multiplicity of an association end.
     * Requires the end to have a property name, since the grammar does not permit
     * explicit multiplicity on association ends without a property.
     *
     * @param node The association end node
     * @param multiplicityText The new multiplicity text
     * @returns A workspace edit for updating the multiplicity
     */
    private async updateAssociationMultiplicity(
        node: AssociationEndType,
        multiplicityText: string
    ): Promise<WorkspaceEdit | undefined> {
        if (node.name == undefined) {
            throw new Error("Cannot set multiplicity on an association end without a property name.");
        }

        const multiplicityAst = parseMultiplicity(multiplicityText);
        if (multiplicityAst == undefined) {
            throw new Error("Invalid multiplicity format.");
        }

        if (node.multiplicity != undefined) {
            const multiplicityNode = GrammarUtils.findNodeForProperty(node.$cstNode, "multiplicity");
            if (multiplicityNode == undefined) {
                throw new Error("Multiplicity CST node not found.");
            }
            return await this.replaceCstNode(multiplicityNode, multiplicityAst);
        } else {
            const nameNode = GrammarUtils.findNodeForProperty(node.$cstNode, "name");
            if (nameNode == undefined) {
                throw new Error("Name CST node not found for association end.");
            }
            const formattedMultiplicity = this.formatMultiplicityAst(multiplicityAst);
            return this.createInsertAfterNodeEdit(nameNode, formattedMultiplicity, false);
        }
    }

    /**
     * Formats a multiplicity AST node as a bracketed text string.
     *
     * Produces strings of the form `[lower..upper]`, `[lower..*]`, or `[value]`
     * without relying on the AST serializer, so it works correctly for synthetic
     * (non-parsed) multiplicity nodes that have no associated CST node.
     *
     * @param multiplicity the multiplicity AST node
     * @returns the formatted multiplicity string, e.g. `[0..1]` or `[*]`
     */
    private formatMultiplicityAst(multiplicity: MultiplicityType): string {
        if (multiplicity.$type === RangeMultiplicity.name) {
            const range = multiplicity as RangeMultiplicityType;
            const upper = range.upper !== undefined ? range.upper : String(range.upperNumeric ?? 1);
            return `[${range.lower}..${upper}]`;
        } else {
            const single = multiplicity as SingleMultiplicityType;
            const value = single.value !== undefined ? single.value : String(single.numericValue ?? 1);
            return `[${value}]`;
        }
    }
}
