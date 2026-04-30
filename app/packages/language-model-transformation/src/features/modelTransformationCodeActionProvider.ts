import type { CodeAction, CodeActionParams, Diagnostic } from "vscode-languageserver";
import type { LangiumDocument } from "langium";
import type { CodeActionProvider } from "langium/lsp";
import type { MaybePromise } from "langium";
import type { DiagnosticData } from "langium";
import type { AstReflection, ExtendedLangiumServices } from "@mdeo/language-common";
import {
    PatternLink,
    PatternObjectInstance,
    type PatternLinkType,
    type PatternObjectInstanceType
} from "../grammar/modelTransformationTypes.js";
import { ModelTransformationIssueCodes } from "../validation/modelTransformationValidator.js";
import { sharedImport } from "@mdeo/language-shared";

const { CstUtils, AstUtils, GrammarUtils } = sharedImport("langium");
const { CodeActionKind } = sharedImport("vscode-languageserver-protocol");

/**
 * Extended diagnostic data for link endpoint modifier mismatch quick fix.
 */
interface LinkEndpointMismatchData extends DiagnosticData {
    endpointModifier: string;
}

/**
 * Code action provider for the model transformation language.
 * Provides quick fixes for modifier consistency issues in patterns.
 */
export class ModelTransformationCodeActionProvider implements CodeActionProvider {
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
            case ModelTransformationIssueCodes.LinkEndpointModifierMismatch:
                return this.fixLinkEndpointModifierMismatch(
                    diagnostic,
                    document,
                    data as unknown as LinkEndpointMismatchData
                );
            case ModelTransformationIssueCodes.InstanceModifierLinkMismatch:
                return this.fixInstanceModifierLinkMismatch(diagnostic, document);
            default:
                return [];
        }
    }

    /**
     * Finds the PatternLink node containing the position indicated by a diagnostic range.
     *
     * @param document The document to search.
     * @param diagnostic The diagnostic whose range is used to locate the node.
     * @returns The PatternLink node at the diagnostic range, or `undefined` if not found.
     */
    private findPatternLinkAtDiagnostic(
        document: LangiumDocument,
        diagnostic: Diagnostic
    ): PatternLinkType | undefined {
        const rootCst = document.parseResult.value.$cstNode;
        if (rootCst == undefined) {
            return undefined;
        }
        const offset = document.textDocument.offsetAt(diagnostic.range.start);
        const leaf = CstUtils.findLeafNodeAtOffset(rootCst, offset);
        if (leaf == undefined) {
            return undefined;
        }
        return AstUtils.getContainerOfType(leaf.astNode, (n) => this.reflection.isInstance(n, PatternLink)) as
            | PatternLinkType
            | undefined;
    }

    /**
     * Finds the PatternObjectInstance node containing the position indicated by a diagnostic range.
     *
     * @param document The document to search.
     * @param diagnostic The diagnostic whose range is used to locate the node.
     * @returns The PatternObjectInstance node at the diagnostic range, or `undefined` if not found.
     */
    private findPatternObjectInstanceAtDiagnostic(
        document: LangiumDocument,
        diagnostic: Diagnostic
    ): PatternObjectInstanceType | undefined {
        const rootCst = document.parseResult.value.$cstNode;
        if (rootCst == undefined) {
            return undefined;
        }
        const offset = document.textDocument.offsetAt(diagnostic.range.start);
        const leaf = CstUtils.findLeafNodeAtOffset(rootCst, offset);
        if (leaf == undefined) {
            return undefined;
        }
        return AstUtils.getContainerOfType(leaf.astNode, (n) =>
            this.reflection.isInstance(n, PatternObjectInstance)
        ) as PatternObjectInstanceType | undefined;
    }

    /**
     * Creates quick fixes for a link whose modifier does not match an endpoint's modifier.
     *
     * Options offered:
     * - If the link has a modifier: change it to the endpoint's modifier, or remove it
     * - If the link has no modifier: add the endpoint's modifier
     *
     * @param diagnostic The diagnostic to attach the quick fixes to.
     * @param document The document containing the link.
     * @param data The diagnostic data with the endpoint modifier.
     * @returns An array of quick fix code actions.
     */
    private fixLinkEndpointModifierMismatch(
        diagnostic: Diagnostic,
        document: LangiumDocument,
        data: LinkEndpointMismatchData
    ): Array<CodeAction | undefined> {
        const link = this.findPatternLinkAtDiagnostic(document, diagnostic);
        if (link == undefined || link.$cstNode == undefined) {
            return [];
        }

        const actions: Array<CodeAction | undefined> = [];
        const uri = document.textDocument.uri;
        const endpointModifier = data.endpointModifier;

        const modifierCstNode = link.modifier?.$cstNode;
        const sourceCstNode = GrammarUtils.findNodeForProperty(link.$cstNode, "source");

        if (modifierCstNode != undefined) {
            actions.push({
                title: `Change link modifier to '${endpointModifier}'`,
                kind: CodeActionKind.QuickFix,
                diagnostics: [diagnostic],
                edit: {
                    changes: {
                        [uri]: [
                            {
                                range: modifierCstNode.range,
                                newText: endpointModifier
                            }
                        ]
                    }
                }
            });

            if (sourceCstNode != undefined) {
                actions.push({
                    title: "Remove link modifier",
                    kind: CodeActionKind.QuickFix,
                    diagnostics: [diagnostic],
                    edit: {
                        changes: {
                            [uri]: [
                                {
                                    range: {
                                        start: modifierCstNode.range.start,
                                        end: sourceCstNode.range.start
                                    },
                                    newText: ""
                                }
                            ]
                        }
                    }
                });
            }
        } else if (sourceCstNode != undefined) {
            actions.push({
                title: `Add '${endpointModifier}' modifier to link`,
                kind: CodeActionKind.QuickFix,
                diagnostics: [diagnostic],
                edit: {
                    changes: {
                        [uri]: [
                            {
                                range: {
                                    start: sourceCstNode.range.start,
                                    end: sourceCstNode.range.start
                                },
                                newText: `${endpointModifier} `
                            }
                        ]
                    }
                }
            });
        }

        return actions;
    }

    /**
     * Creates a quick fix for an instance whose modifier is not compatible with its connected links.
     *
     * Option offered:
     * - Remove the instance modifier
     *
     * @param diagnostic The diagnostic to attach the quick fix to.
     * @param document The document containing the instance.
     * @returns An array of quick fix code actions.
     */
    private fixInstanceModifierLinkMismatch(
        diagnostic: Diagnostic,
        document: LangiumDocument
    ): Array<CodeAction | undefined> {
        const instance = this.findPatternObjectInstanceAtDiagnostic(document, diagnostic);
        if (instance == undefined || instance.$cstNode == undefined) {
            return [];
        }

        const modifierCstNode = instance.modifier?.$cstNode;
        if (modifierCstNode == undefined) {
            return [];
        }

        const nameCstNode = GrammarUtils.findNodeForProperty(instance.$cstNode, "name");
        if (nameCstNode == undefined) {
            return [];
        }

        const uri = document.textDocument.uri;
        return [
            {
                title: "Remove instance modifier",
                kind: CodeActionKind.QuickFix,
                diagnostics: [diagnostic],
                edit: {
                    changes: {
                        [uri]: [
                            {
                                range: {
                                    start: modifierCstNode.range.start,
                                    end: nameCstNode.range.start
                                },
                                newText: ""
                            }
                        ]
                    }
                }
            }
        ];
    }
}
