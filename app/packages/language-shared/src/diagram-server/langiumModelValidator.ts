import { CstUtils, type AstNode, type LangiumDocument } from "langium";
import type { Marker as GlspMarker } from "@eclipse-glsp/protocol";
import type { GModelElement as GModelElementType } from "@eclipse-glsp/server";
import type { DiagnosticInfo, ValidationSeverity } from "langium";
import type { Diagnostic } from "vscode-languageserver-types";
import type { ModelState } from "./modelState.js";
import type { GModelIndex } from "./modelIndex.js";
import { GNode } from "./model/node.js";
import { GEdge } from "./model/edge.js";
import { sharedImport } from "../sharedImport.js";

const { injectable, inject } = sharedImport("inversify");
const { ModelState: ModelStateKey, GModelIndex: GModelIndexKey } = sharedImport("@eclipse-glsp/server");
const { MarkerKind } = sharedImport("@eclipse-glsp/protocol");

/**
 * Internal representation of a validation issue that retains the reference to the originating AST node.
 * This bridges the LSP {@link Diagnostic} world (stored on the document) with the AST node hierarchy.
 *
 * @template N The AST node type this issue is associated with
 */
export interface AstValidationIssue<N extends AstNode = AstNode> {
    /**
     * The severity level of the validation issue.
     */
    severity: ValidationSeverity;

    /**
     * A human-readable description of the issue.
     */
    message: string;

    /**
     * The diagnostic info containing the AST node reference and optional property/range details.
     */
    info: DiagnosticInfo<N>;
}

/**
 * A model validator that bridges Langium's document validation with GLSP's marker-based validation system.
 *
 * This validator intercepts Langium's internal validation format ({@link DiagnosticInfo} with AST node references)
 * and maps each issue to the appropriate graphical model element using the {@link GModelIndex}. When a direct
 * mapping is not possible (e.g., the AST node has no graphical representation), the validator walks up the
 * AST parent chain until it finds a node with a corresponding graphical element.
 *
 * The validator also ensures markers are only placed on "acceptable" graphical elements (by default
 * {@link GNode} and {@link GEdge}), walking up the GModel parent chain if necessary.
 *
 * Subclasses can override {@link isAcceptableMarkerTarget} and {@link collectIssues} to customize behavior.
 */
@injectable()
export class LangiumModelValidator {
    /**
     * The current model state, providing access to the source model and graphical model root.
     */
    @inject(ModelStateKey) protected readonly modelState!: ModelState;

    /**
     * The model index for resolving AST nodes to graphical element IDs and vice versa.
     */
    @inject(GModelIndexKey) protected readonly modelIndex!: GModelIndex;

    /**
     * Validates all graphical model elements by collecting issues from the current Langium document
     * and converting them into GLSP markers.
     *
     * @param _elements The graphical model elements to validate (unused; issues are sourced from Langium)
     * @param _reason The reason for the validation request (e.g., "batch" or "live")
     * @returns An array of GLSP {@link Marker} instances representing the validation issues
     */
    validate(_elements: GModelElementType[], _reason?: string): GlspMarker[] {
        const sourceModel = this.modelState.sourceModel;
        if (!sourceModel) {
            return [];
        }

        const document = sourceModel.$document;
        if (!document) {
            return [];
        }

        const issues = this.collectIssues(document);
        return this.convertIssuesToMarkers(issues);
    }

    /**
     * Collects validation issues from the Langium document's diagnostics and maps each one back
     * to the originating AST node using the CST.
     *
     * Subclasses can override this method to add or filter issues.
     *
     * @param document The Langium document to collect issues from
     * @returns An array of issues with AST node references
     */
    protected collectIssues(document: LangiumDocument): AstValidationIssue[] {
        const issues: AstValidationIssue[] = [];
        const diagnostics = document.diagnostics ?? [];

        for (const diagnostic of diagnostics) {
            const astNode = this.findAstNodeForDiagnostic(document, diagnostic);
            if (!astNode) {
                continue;
            }

            issues.push({
                severity: this.diagnosticSeverityToValidationSeverity(diagnostic.severity ?? 1),
                message: diagnostic.message,
                info: {
                    node: astNode,
                    range: diagnostic.range
                }
            });
        }

        return issues;
    }

    /**
     * Finds the AST node that corresponds to a given LSP diagnostic by converting the diagnostic's
     * start position to a document offset, finding the leaf CST node at that offset via
     * {@link CstUtils.findLeafNodeAtOffset}, and returning its associated AST node.
     *
     * Falls back to the parse result root if no CST leaf is found at the offset.
     *
     * @param document The Langium document containing the AST and text document
     * @param diagnostic The LSP diagnostic with range information
     * @returns The matching AST node, or the document root as fallback
     */
    private findAstNodeForDiagnostic(document: LangiumDocument, diagnostic: Diagnostic): AstNode | undefined {
        const root = document.parseResult?.value;
        if (!root) {
            return undefined;
        }
        const rootCstNode = root.$cstNode;
        if (!rootCstNode) {
            return root;
        }
        const offset = document.textDocument.offsetAt(diagnostic.range.start);
        const leafCstNode = CstUtils.findLeafNodeAtOffset(rootCstNode, offset);
        return leafCstNode?.astNode ?? root;
    }

    /**
     * Converts the LSP {@code DiagnosticSeverity} numeric value to a Langium {@link ValidationSeverity} string.
     *
     * @param severity The LSP diagnostic severity (1=Error, 2=Warning, 3=Information, 4=Hint)
     * @returns The corresponding validation severity string
     */
    private diagnosticSeverityToValidationSeverity(severity: number): ValidationSeverity {
        switch (severity) {
            case 1:
                return "error";
            case 2:
                return "warning";
            case 3:
                return "info";
            case 4:
                return "hint";
            default:
                return "error";
        }
    }

    /**
     * Converts a list of AST-level validation issues into GLSP markers by resolving each issue's
     * AST node to a graphical model element.
     *
     * For each issue, the method:
     * 1. Resolves the AST node to a graphical element via the model index
     * 2. If no direct mapping exists, walks up the AST parent chain
     * 3. Checks if the resolved element is an acceptable marker target
     * 4. If not acceptable, walks up the GModel parent chain until an acceptable target is found
     *
     * @param issues The validation issues with AST node references
     * @returns An array of GLSP markers
     */
    private convertIssuesToMarkers(issues: AstValidationIssue[]): GlspMarker[] {
        const markers: GlspMarker[] = [];

        for (const issue of issues) {
            const element = this.resolveElement(issue.info.node);
            if (element == undefined) {
                continue;
            }

            const targetId = this.findAcceptableTargetId(element);
            if (targetId == undefined) {
                continue;
            }

            markers.push({
                elementId: targetId,
                kind: this.severityToMarkerKind(issue.severity),
                label: issue.message,
                description: issue.message
            });
        }

        return markers;
    }

    /**
     * Resolves an AST node to a graphical model element by querying the model index.
     * If the node has no direct mapping, or the ID maps to no existing element, walks up
     * the AST parent chain until a mapped element is found.
     *
     * @param node The AST node to resolve
     * @returns The graphical model element, or {@code undefined} if no element is found even at the root
     */
    private resolveElement(node: AstNode): GModelElementType | undefined {
        let current: AstNode | undefined = node;
        while (current != undefined) {
            const id = this.modelIndex.getElementId(current);
            if (id !== undefined) {
                const element = this.modelIndex.find(id);
                if (element !== undefined) {
                    return element;
                }
            }
            current = current.$container;
        }
        const rootId = this.modelState.root?.id;
        if (rootId !== undefined) {
            return this.modelIndex.get(rootId);
        }
        return undefined;
    }

    /**
     * Finds an acceptable marker target by starting at the given element and walking up
     * the GModel parent chain until an element passing {@link isAcceptableMarkerTarget} is found.
     *
     * @param element The starting graphical element
     * @returns The ID of an acceptable target element, or {@code undefined} if none is found
     */
    private findAcceptableTargetId(element: GModelElementType): string | undefined {
        let current: GModelElementType | undefined = element;
        while (current) {
            if (this.isAcceptableMarkerTarget(current)) {
                return current.id;
            }
            current = current.parent;
        }
        return undefined;
    }

    /**
     * Determines whether a graphical model element is an acceptable target for a validation marker.
     *
     * By default, only {@link GNode} and {@link GEdge} instances are accepted. The root element
     * is also accepted as a fallback so that issues always have a target.
     *
     * Subclasses can override this method to accept additional element types.
     *
     * @param element The graphical model element to check
     * @returns {@code true} if the element is an acceptable marker target
     */
    protected isAcceptableMarkerTarget(element: GModelElementType): boolean {
        if (element === this.modelState.root) {
            return true;
        }
        return element instanceof GNode || element instanceof GEdge;
    }

    /**
     * Converts a Langium {@link ValidationSeverity} to a GLSP {@link MarkerKind}.
     *
     * @param severity The validation severity
     * @returns The corresponding GLSP marker kind
     */
    private severityToMarkerKind(severity: ValidationSeverity): string {
        switch (severity) {
            case "error":
                return MarkerKind.ERROR;
            case "warning":
                return MarkerKind.WARNING;
            case "info":
                return MarkerKind.INFO;
            case "hint":
                return MarkerKind.INFO;
            default:
                return MarkerKind.ERROR;
        }
    }
}
