import type { CodeAction, CodeActionParams, Diagnostic } from "vscode-languageserver";
import type { LangiumDocument } from "langium";
import type { CodeActionProvider } from "langium/lsp";
import type { MaybePromise } from "langium";
import type { DiagnosticData } from "langium";
import type { AstReflection, ExtendedLangiumServices } from "@mdeo/language-common";
import { Association, type AssociationType, type AssociationEndType } from "../grammar/metamodelTypes.js";
import { MetamodelIssueCodes } from "../validation/metamodelValidator.js";
import { sharedImport } from "@mdeo/language-shared";

const { CstUtils, AstUtils, GrammarUtils } = sharedImport("langium");
const { CodeActionKind } = sharedImport("vscode-languageserver-protocol");

/**
 * Extended diagnostic data for association property mismatch quick fixes.
 */
interface AssociationPropertyData extends DiagnosticData {
    /** Suggested operator to switch to if the user wants to change the operator instead. Undefined if no good alternative. */
    suggestedOperator?: string;
}

/**
 * Code action provider for the metamodel language.
 * Provides quick fixes for association end property name inconsistencies.
 */
export class MetamodelCodeActionProvider implements CodeActionProvider {
    private readonly reflection: AstReflection;

    constructor(services: ExtendedLangiumServices) {
        this.reflection = services.shared.AstReflection;
    }

    /**
     * Returns all code actions for the given document and parameters.
     *
     * @param document The document to generate code actions for.
     * @param params The code action parameters including diagnostics.
     * @returns An array of code actions.
     */
    getCodeActions(document: LangiumDocument, params: CodeActionParams): MaybePromise<Array<CodeAction>> {
        const result: CodeAction[] = [];
        for (const diagnostic of params.context.diagnostics) {
            const data = diagnostic.data as (DiagnosticData & Record<string, unknown>) | undefined;
            if (data == undefined || data.code == undefined) {
                continue;
            }
            const actions = this.createCodeActions(diagnostic, document, data);
            for (const action of actions) {
                if (action != undefined) {
                    result.push(action);
                }
            }
        }
        return result;
    }

    /**
     * Dispatches to the appropriate quick fix method based on the diagnostic code.
     *
     * @param diagnostic The diagnostic to create actions for.
     * @param document The document containing the diagnostic.
     * @param data The diagnostic data including the issue code.
     * @returns An array of optional code actions.
     */
    private createCodeActions(
        diagnostic: Diagnostic,
        document: LangiumDocument,
        data: DiagnosticData & Record<string, unknown>
    ): Array<CodeAction | undefined> {
        switch (data.code) {
            case MetamodelIssueCodes.AssociationMissingSourceName:
                return this.fixMissingEndName(
                    diagnostic,
                    document,
                    "source",
                    data as unknown as AssociationPropertyData
                );
            case MetamodelIssueCodes.AssociationMissingTargetName:
                return this.fixMissingEndName(
                    diagnostic,
                    document,
                    "target",
                    data as unknown as AssociationPropertyData
                );
            case MetamodelIssueCodes.AssociationExtraSourceName:
                return this.fixExtraEndName(diagnostic, document, "source", data as unknown as AssociationPropertyData);
            case MetamodelIssueCodes.AssociationExtraTargetName:
                return this.fixExtraEndName(diagnostic, document, "target", data as unknown as AssociationPropertyData);
            default:
                return [];
        }
    }

    /**
     * Finds the Association node containing the position indicated by a diagnostic range.
     *
     * @param document The document to search.
     * @param diagnostic The diagnostic whose range is used to locate the node.
     * @returns The Association node at the diagnostic range, or `undefined` if not found.
     */
    private findAssociationAtDiagnostic(
        document: LangiumDocument,
        diagnostic: Diagnostic
    ): AssociationType | undefined {
        const rootCst = document.parseResult.value.$cstNode;
        if (rootCst == undefined) {
            return undefined;
        }
        const offset = document.textDocument.offsetAt(diagnostic.range.start);
        const leaf = CstUtils.findLeafNodeAtOffset(rootCst, offset);
        if (leaf == undefined) {
            return undefined;
        }
        return AstUtils.getContainerOfType(leaf.astNode, (n) => this.reflection.isInstance(n, Association)) as
            | AssociationType
            | undefined;
    }

    /**
     * Creates quick fixes for a missing property name on an association end.
     *
     * Options offered:
     * - Add a default property name to the end (derived from the class name)
     * - Change the operator to one that does not require a name on this end (if applicable)
     *
     * @param diagnostic The diagnostic to attach the quick fixes to.
     * @param document The document containing the association.
     * @param end Which association end to fix (`"source"` or `"target"`).
     * @param data The diagnostic data with the optional suggested operator.
     * @returns An array of quick fix code actions.
     */
    private fixMissingEndName(
        diagnostic: Diagnostic,
        document: LangiumDocument,
        end: "source" | "target",
        data: AssociationPropertyData
    ): Array<CodeAction | undefined> {
        const association = this.findAssociationAtDiagnostic(document, diagnostic);
        if (association == undefined || association.$cstNode == undefined) {
            return [];
        }

        const endNode: AssociationEndType | undefined = end === "source" ? association.source : association.target;
        if (endNode == undefined || endNode.$cstNode == undefined) {
            return [];
        }

        const actions: Array<CodeAction | undefined> = [];
        const uri = document.textDocument.uri;

        const className = endNode.class?.ref?.name;
        const defaultName = className != undefined ? this.toPropertyName(className) : "property";

        const classCstNode = GrammarUtils.findNodeForProperty(endNode.$cstNode, "class");
        if (classCstNode != undefined) {
            actions.push({
                title: `Add property name '${defaultName}' to ${end} end`,
                kind: CodeActionKind.QuickFix,
                diagnostics: [diagnostic],
                isPreferred: true,
                edit: {
                    changes: {
                        [uri]: [
                            {
                                range: {
                                    start: classCstNode.range.end,
                                    end: classCstNode.range.end
                                },
                                newText: `.${defaultName}`
                            }
                        ]
                    }
                }
            });
        }

        if (data.suggestedOperator != undefined) {
            const operatorCstNode = GrammarUtils.findNodeForProperty(association.$cstNode, "operator");
            if (operatorCstNode != undefined) {
                actions.push({
                    title: `Change operator to '${data.suggestedOperator}'`,
                    kind: CodeActionKind.QuickFix,
                    diagnostics: [diagnostic],
                    edit: {
                        changes: {
                            [uri]: [
                                {
                                    range: operatorCstNode.range,
                                    newText: data.suggestedOperator
                                }
                            ]
                        }
                    }
                });
            }
        }

        return actions;
    }

    /**
     * Creates quick fixes for an extra (unwanted) property name on an association end.
     *
     * Options offered:
     * - Remove the property name (and multiplicity) from the end
     * - Change the operator to one that requires a name on this end (if applicable)
     *
     * @param diagnostic The diagnostic to attach the quick fixes to.
     * @param document The document containing the association.
     * @param end Which association end to fix (`"source"` or `"target"`).
     * @param data The diagnostic data with the optional suggested operator.
     * @returns An array of quick fix code actions.
     */
    private fixExtraEndName(
        diagnostic: Diagnostic,
        document: LangiumDocument,
        end: "source" | "target",
        data: AssociationPropertyData
    ): Array<CodeAction | undefined> {
        const association = this.findAssociationAtDiagnostic(document, diagnostic);
        if (association == undefined || association.$cstNode == undefined) {
            return [];
        }

        const endNode: AssociationEndType | undefined = end === "source" ? association.source : association.target;
        if (endNode == undefined || endNode.$cstNode == undefined) {
            return [];
        }

        const actions: Array<CodeAction | undefined> = [];
        const uri = document.textDocument.uri;

        const classCstNode = GrammarUtils.findNodeForProperty(endNode.$cstNode, "class");
        if (classCstNode != undefined) {
            const removeRange = {
                start: classCstNode.range.end,
                end: endNode.$cstNode.range.end
            };
            const removedText = document.textDocument.getText(removeRange);
            if (removedText.trim().length > 0) {
                actions.push({
                    title: `Remove property name from ${end} end`,
                    kind: CodeActionKind.QuickFix,
                    diagnostics: [diagnostic],
                    isPreferred: true,
                    edit: {
                        changes: {
                            [uri]: [
                                {
                                    range: removeRange,
                                    newText: ""
                                }
                            ]
                        }
                    }
                });
            }
        }

        if (data.suggestedOperator != undefined) {
            const operatorCstNode = GrammarUtils.findNodeForProperty(association.$cstNode, "operator");
            if (operatorCstNode != undefined) {
                actions.push({
                    title: `Change operator to '${data.suggestedOperator}'`,
                    kind: CodeActionKind.QuickFix,
                    diagnostics: [diagnostic],
                    edit: {
                        changes: {
                            [uri]: [
                                {
                                    range: operatorCstNode.range,
                                    newText: data.suggestedOperator
                                }
                            ]
                        }
                    }
                });
            }
        }

        return actions;
    }

    /**
     * Converts a class name to a default property name (first letter lowercase).
     * For example: `"House"` → `"house"`, `"MyClass"` → `"myClass"`.
     *
     * @param className The class name to convert.
     * @returns The property name derived from the class name.
     */
    private toPropertyName(className: string): string {
        if (className.length === 0) {
            return "property";
        }
        return className.charAt(0).toLowerCase() + className.slice(1);
    }
}
