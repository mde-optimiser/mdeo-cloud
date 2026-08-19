import { BaseOperationHandler, OperationHandlerCommand, sharedImport } from "@mdeo/language-shared";
import { Class, type ClassType } from "../../../grammar/metamodelTypes.js";
import type { AstNode } from "langium";
import type { Command } from "@eclipse-glsp/server";
import type { WorkspaceEdit, Range } from "vscode-languageserver-types";
import type { ContextItem } from "@mdeo/protocol-common";
import type { GModelElement } from "@eclipse-glsp/server";
import type { ContextActionRequestContext, ContextItemProvider } from "@mdeo/language-shared";
import { ToggleAbstractClassOperation, MetamodelElementType } from "@mdeo/protocol-metamodel";
import type { GClassNode } from "../model/classNode.js";
import { isImportedElement } from "./metamodelHandlerUtils.js";

const { injectable } = sharedImport("inversify");
const { GrammarUtils } = sharedImport("langium");

/**
 * Handler for toggling the abstract modifier on a class.
 */
@injectable()
export class ToggleAbstractClassOperationHandler extends BaseOperationHandler implements ContextItemProvider {
    override readonly operationType = ToggleAbstractClassOperation.KIND;

    /**
     * Creates a workspace-edit command that toggles class abstractness.
     *
     * @param operation The toggle operation
     * @returns A command wrapping the workspace edit, or undefined
     */
    override async createCommand(operation: ToggleAbstractClassOperation): Promise<Command | undefined> {
        const gmodelElement = this.modelState.index.get(operation.classId);
        if (gmodelElement == undefined) {
            return undefined;
        }

        const astNode = this.index.getAstNode(gmodelElement);
        if (astNode == undefined || !this.reflection.isInstance(astNode, Class)) {
            return undefined;
        }

        const classNode = astNode as ClassType;
        const currentAbstract = classNode.isAbstract ?? false;
        const targetAbstract = operation.makeAbstract !== undefined ? operation.makeAbstract : !currentAbstract;

        if (targetAbstract === currentAbstract) {
            return undefined;
        }

        const edit = await this.createAbstractToggleEdit(classNode, targetAbstract);
        if (edit == undefined) {
            return undefined;
        }

        return new OperationHandlerCommand(this.modelState, edit, undefined);
    }

    /**
     * Returns context items for toggling abstractness on class nodes.
     *
     * @param element The selected element
     * @param _context Request context
     * @returns Context actions provided by this handler
     */
    getContextItems(element: GModelElement, _context: ContextActionRequestContext): ContextItem[] {
        if (element.type !== MetamodelElementType.NODE_CLASS) {
            return [];
        }

        if (isImportedElement(element)) {
            return [];
        }
        const isAbstract = (element as GClassNode).isAbstract ?? false;

        return [
            {
                id: `toggle-abstract-${element.id}`,
                label: isAbstract ? "Make Concrete" : "Make Abstract",
                icon: isAbstract ? "square" : "square-dashed",
                sortString: "b",
                action: ToggleAbstractClassOperation.create({
                    classId: element.id
                })
            }
        ];
    }

    /**
     * Builds a workspace edit to add or remove the abstract keyword.
     *
     * @param classNode The class AST node
     * @param makeAbstract Whether the class should become abstract
     * @returns The workspace edit, or undefined if CST nodes are missing
     */
    private async createAbstractToggleEdit(
        classNode: AstNode,
        makeAbstract: boolean
    ): Promise<WorkspaceEdit | undefined> {
        const cstNode = classNode.$cstNode;
        if (cstNode == undefined) {
            return undefined;
        }

        const classKeyword = GrammarUtils.findNodeForKeyword(cstNode, "class");
        if (classKeyword == undefined) {
            return undefined;
        }

        if (makeAbstract) {
            return this.replaceCstNode(classKeyword, "abstract class");
        }

        const abstractKeyword = GrammarUtils.findNodeForKeyword(cstNode, "abstract");
        if (abstractKeyword == undefined) {
            return undefined;
        }
        const range: Range = { start: abstractKeyword.range.start, end: classKeyword.range.end };
        const uri = this.modelState.sourceUri!;
        return { changes: { [uri]: [{ range, newText: "class" }] } };
    }
}
