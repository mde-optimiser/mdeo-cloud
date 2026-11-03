import type { AstReflection, ExtendedLangiumServices } from "@mdeo/language-common";
import { sharedImport } from "@mdeo/language-shared";
import {
    describeTransformationInputs,
    renderDeclarations,
    transformationDisplayName,
    type ModelTransformationType
} from "@mdeo/language-model-transformation";
import type { AstNode, CstNode, LangiumDocument, LangiumDocuments } from "langium";
import type { Hover, HoverParams } from "vscode-languageserver-protocol";
import { OperatorArgument, UsingPath, type OperatorArgumentType, type UsingPathType } from "../grammar/mdeoTypes.js";
import { argumentName, findUsedTransformation, renderDeclaration } from "./operatorArguments.js";
import { isPassableInput } from "../validation/operatorArgumentsValidator.js";

const { MultilineCommentHoverProvider } = sharedImport("langium/lsp");
const { AstUtils, CstUtils, GrammarAST } = sharedImport("langium");

/**
 * Hover provider of the MDEO config sections: the declarations of the transformation a `using`
 * entry names, and the declaration of the input an operator argument sets.
 */
export class MdeoHoverProvider extends MultilineCommentHoverProvider {
    private readonly documents: LangiumDocuments;
    private readonly reflection: AstReflection;

    /**
     * @param services The language services
     */
    constructor(services: ExtendedLangiumServices) {
        super(services);
        this.documents = services.shared.workspace.LangiumDocuments;
        this.reflection = services.shared.AstReflection;
    }

    override async getHoverContent(document: LangiumDocument, params: HoverParams): Promise<Hover | undefined> {
        return this.operatorHover(document, params) ?? super.getHoverContent(document, params);
    }

    /**
     * Returns the hover for the path of a using entry or the name of an operator argument, if
     * the position is on one.
     */
    private operatorHover(document: LangiumDocument, params: HoverParams): Hover | undefined {
        const rootCstNode = document.parseResult?.value?.$cstNode;
        if (rootCstNode == undefined) {
            return undefined;
        }
        const offset = document.textDocument.offsetAt(params.position);
        const leaf = CstUtils.findLeafNodeAtOffset(rootCstNode, offset);
        if (leaf == undefined) {
            return undefined;
        }
        const astNode = leaf.astNode;
        const feature = assignedFeature(leaf, astNode);
        if (this.reflection.isInstance(astNode, UsingPath) && feature === "path") {
            return this.declarationsHover(astNode as UsingPathType);
        }
        if (this.reflection.isInstance(astNode, OperatorArgument) && feature === "name") {
            return this.argumentHover(astNode as OperatorArgumentType);
        }
        return undefined;
    }

    /**
     * Hover showing the `in` / `out` declarations of the transformation a using entry names.
     */
    private declarationsHover(usingPath: UsingPathType): Hover | undefined {
        const transformation = findUsedTransformation(usingPath, this.documents, this.reflection);
        if (transformation == undefined) {
            return undefined;
        }
        const file = AstUtils.getDocument(transformation).uri.path;
        return markdownHover(this.renderAllDeclarations(transformation), `Declared in \`${file}\``);
    }

    /**
     * Renders the `in` / `out` declarations of a transformation, one per line.
     */
    private renderAllDeclarations(transformation: ModelTransformationType): string {
        const parameters = transformation.parameters ?? [];
        if (parameters.length === 0 || parameters.every((p) => p.type?.$cstNode != undefined)) {
            return renderDeclarations(transformation);
        }
        return parameters.map((p) => renderDeclaration(p, this.reflection)).join("\n");
    }

    /**
     * Hover showing the declaration of the input an operator argument sets.
     */
    private argumentHover(argument: OperatorArgumentType): Hover | undefined {
        const usingPath = AstUtils.getContainerOfType(argument, (n): n is UsingPathType =>
            this.reflection.isInstance(n, UsingPath)
        );
        if (usingPath == undefined) {
            return undefined;
        }
        const transformation = findUsedTransformation(usingPath, this.documents, this.reflection);
        if (transformation == undefined) {
            return undefined;
        }
        const name = argumentName(argument);
        const parameter = (transformation.parameters ?? []).find((p) => p.name === name);
        if (parameter == undefined) {
            return undefined;
        }
        const fileName = transformationDisplayName(transformation);
        if (parameter.direction !== "in") {
            return markdownHover(renderDeclaration(parameter, this.reflection), `output of \`${fileName}\``);
        }
        const input = describeTransformationInputs(transformation, this.reflection, this.documents).find(
            (i) => i.name === name
        );
        const role = input?.optional === true ? "optional input" : "required input";
        const note = input != undefined && !isPassableInput(input) ? " (cannot be set from the config)" : "";
        return markdownHover(renderDeclaration(parameter, this.reflection), `${role} of \`${fileName}\`${note}`);
    }
}

/**
 * Returns the property a CST node is assigned to within its AST node, also for the tokens of a
 * data type rule (e.g. the keyword `size` of an argument name).
 *
 * @param leaf The CST node at the position
 * @param astNode The AST node it belongs to
 * @returns The property name, if the node is part of an assignment
 */
function assignedFeature(leaf: CstNode, astNode: AstNode): string | undefined {
    let current: CstNode | undefined = leaf;
    while (current != undefined && current.astNode === astNode) {
        const assignment = AstUtils.getContainerOfType(current.grammarSource, GrammarAST.isAssignment);
        if (assignment != undefined) {
            return assignment.feature;
        }
        current = current.container;
    }
    return undefined;
}

/**
 * Creates a markdown hover with a code block and an explanation.
 *
 * @param code The code
 * @param text The explanation below it
 * @returns The hover
 */
function markdownHover(code: string, text: string): Hover {
    return {
        contents: {
            kind: "markdown",
            value: "```\n" + code + "\n```\n" + text
        }
    };
}
