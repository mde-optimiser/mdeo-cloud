import { ExpressionHoverProvider } from "@mdeo/language-expression";
import { sharedImport } from "@mdeo/language-shared";
import type { AstNode, LangiumDocument, LangiumDocuments, MaybePromise } from "langium";
import type { Hover, HoverParams } from "vscode-languageserver-protocol";
import {
    getCallee,
    CallArgument,
    CallOutput,
    CallStatement,
    PatternLinkEnd,
    PatternModelScope,
    PatternObjectInstance,
    PatternObjectInstanceDelete,
    PatternObjectInstanceReference,
    TransformationFileImport,
    TransformationParameter,
    ModelTransformation,
    type CallOutputType,
    type ModelTransformationType,
    type PatternObjectInstanceType,
    type TransformationFileImportType,
    type TransformationParameterType
} from "../grammar/modelTransformationTypes.js";
import {
    TransformationDeclarationResolver,
    getTransformationRoot,
    renderDeclarations,
    renderParameter,
    transformationDisplayName
} from "./transformationDeclarations.js";
import { resolveRelativeDocument } from "@mdeo/language-shared";

const { CstUtils, AstUtils, GrammarAST } = sharedImport("langium");

/**
 * Hover provider of the Model Transformation language: the expression hovers, plus the
 * declarations of called and imported transformations and the parameters and outputs of calls.
 */
export class ModelTransformationHoverProvider extends ExpressionHoverProvider {
    private readonly documents: LangiumDocuments;

    constructor(...args: ConstructorParameters<typeof ExpressionHoverProvider>) {
        super(...args);
        this.documents = args[0].shared.workspace.LangiumDocuments;
    }

    override getHoverContent(document: LangiumDocument, params: HoverParams): MaybePromise<Hover | undefined> {
        const hover = this.callHover(document, params);
        return hover ?? super.getHoverContent(document, params);
    }

    /**
     * Returns the hover for the parts of imports, declarations and calls, if the position is on
     * one.
     */
    private callHover(document: LangiumDocument, params: HoverParams): Hover | undefined {
        const rootCstNode = document.parseResult?.value?.$cstNode;
        if (rootCstNode == undefined) {
            return undefined;
        }
        const offset = document.textDocument.offsetAt(params.position);
        const cstNode = CstUtils.findDeclarationNodeAtOffset(rootCstNode, offset);
        if (cstNode == undefined) {
            return undefined;
        }
        const astNode = cstNode.astNode;
        const feature = AstUtils.getContainerOfType(cstNode.grammarSource, GrammarAST.isAssignment)?.feature;

        if (this.reflection.isInstance(astNode, CallStatement) && feature === "callee") {
            return this.declarationsHover(getCallee(astNode));
        }
        if (
            this.reflection.isInstance(astNode, TransformationFileImport) &&
            (feature === "name" || feature === "file")
        ) {
            return this.declarationsHover(this.importedTransformation(astNode as TransformationFileImportType));
        }
        if (this.reflection.isInstance(astNode, PatternModelScope) && feature === "model") {
            const target = astNode.model?.ref;
            return this.reflection.isInstance(target, TransformationParameter)
                ? this.parameterHover(target as TransformationParameterType)
                : undefined;
        }
        if (this.reflection.isInstance(astNode, TransformationParameter) && feature === "name") {
            return this.parameterHover(astNode);
        }
        if (
            (this.reflection.isInstance(astNode, CallArgument) || this.reflection.isInstance(astNode, CallOutput)) &&
            feature === "parameter"
        ) {
            return this.parameterHover(astNode.parameter?.ref);
        }
        if (this.reflection.isInstance(astNode, CallOutput) && feature === "name") {
            return this.outputHover(astNode);
        }
        if (
            (this.reflection.isInstance(astNode, PatternObjectInstanceReference) ||
                this.reflection.isInstance(astNode, PatternObjectInstanceDelete)) &&
            feature === "instance"
        ) {
            return this.declarationHover(astNode.instance?.ref);
        }
        if (this.reflection.isInstance(astNode, PatternLinkEnd) && feature === "object") {
            return this.declarationHover(astNode.object?.ref);
        }
        if (this.reflection.isInstance(astNode, PatternObjectInstance) && feature === "name") {
            return this.pinnedObjectHover(astNode as PatternObjectInstanceType);
        }
        return undefined;
    }

    /**
     * Hover for an object of the first pattern that an optional element input pins.
     */
    private pinnedObjectHover(object: PatternObjectInstanceType): Hover | undefined {
        const parameter = (getTransformationRoot(this.reflection, object)?.parameters ?? []).find(
            (p) => p.name === object.name && this.declarations().getPinnedObject(p) === object
        );
        if (parameter == undefined) {
            return undefined;
        }
        return markdownHover(
            `${object.name}: ${object.class?.$refText ?? "?"}`,
            `pinned by the optional input \`${renderParameter(parameter)}\` of \`${transformationDisplayName(parameter)}\`: bound to the element a caller passes, matched freely when none is passed`
        );
    }

    /**
     * A resolver for the declarations of the transformation.
     */
    private declarations(): TransformationDeclarationResolver {
        return new TransformationDeclarationResolver(this.reflection, this.documents);
    }

    /**
     * Hover for a referenced declaration that is a parameter or call output.
     */
    private declarationHover(declaration: AstNode | undefined): Hover | undefined {
        if (this.reflection.isInstance(declaration, TransformationParameter)) {
            return this.parameterHover(declaration);
        }
        if (this.reflection.isInstance(declaration, CallOutput)) {
            return this.outputHover(declaration);
        }
        return undefined;
    }

    /**
     * Hover showing the `in` / `out` declarations of a transformation.
     */
    private declarationsHover(transformation: ModelTransformationType | undefined): Hover | undefined {
        if (transformation == undefined) {
            return undefined;
        }
        const file = AstUtils.getDocument(transformation).uri.path;
        return markdownHover(renderDeclarations(transformation), `Declared in \`${file}\``);
    }

    /**
     * Returns the root of the file a path import names, if it is a loaded transformation.
     */
    private importedTransformation(fileImport: TransformationFileImportType): ModelTransformationType | undefined {
        if (fileImport.file == undefined) {
            return undefined;
        }
        const target = resolveRelativeDocument(AstUtils.getDocument(fileImport), fileImport.file, this.documents);
        const root = target?.parseResult?.value;
        return root != undefined && this.reflection.isInstance(root, ModelTransformation)
            ? (root as ModelTransformationType)
            : undefined;
    }

    /**
     * Hover showing one parameter and the transformation it belongs to.
     */
    private parameterHover(parameter: TransformationParameterType | undefined): Hover | undefined {
        if (parameter == undefined) {
            return undefined;
        }
        const nullable = (parameter.type as { isNullable?: boolean } | undefined)?.isNullable === true;
        const pinned = this.declarations().getPinnedObject(parameter);
        const role =
            parameter.direction === "in"
                ? pinned != undefined
                    ? `optional input that pins the object \`${pinned.name}\` of the first pattern (matched freely when not passed)`
                    : parameter.defaultValue != undefined || nullable
                      ? "optional input"
                      : "required input"
                : "output";
        return markdownHover(renderParameter(parameter), `${role} of \`${transformationDisplayName(parameter)}\``);
    }

    /**
     * Hover showing the name a call output binds and the callee parameter it comes from.
     */
    private outputHover(output: CallOutputType): Hover | undefined {
        const parameter = output.parameter?.ref;
        const typeText = parameter?.type?.$cstNode?.text ?? "?";
        const callee = (output.$container as { callee?: { $refText?: string } } | undefined)?.callee?.$refText;
        return markdownHover(
            `${output.name}: ${typeText}`,
            `bound by the call: output \`${parameter?.name ?? output.parameter?.$refText}\` of \`${callee ?? "?"}\``
        );
    }
}

/**
 * Creates a markdown hover with a code line and an explanation.
 *
 * @param code The code line
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
