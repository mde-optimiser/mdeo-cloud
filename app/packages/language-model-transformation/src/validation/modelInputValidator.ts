import type { AstNode, ValidationAcceptor } from "langium";
import type { AstReflection, ExtendedLangiumServices } from "@mdeo/language-common";
import { sharedImport } from "@mdeo/language-shared";
import {
    AssignmentStatement,
    CallArgument,
    ForInStatement,
    LambdaExpression,
    PatternApplicationCondition,
    PatternCollectionAppend,
    PatternLink,
    PatternModelScope,
    PatternObjectInstance,
    PatternObjectInstanceDelete,
    PatternObjectInstanceReference,
    PatternVariableReassignment,
    TransformationParameter,
    expressionTypes,
    typeTypes,
    type CallArgumentType,
    type ForInStatementType,
    type LambdaExpressionType,
    type ModelTransformationType,
    type PatternLinkType,
    type PatternModelScopeType,
    type PatternObjectInstanceDeleteType,
    type PatternObjectInstanceReferenceType,
    type PatternObjectInstanceType,
    type PatternPropertyAssignmentType,
    type TransformationParameterType
} from "../grammar/modelTransformationTypes.js";
import {
    TransformationDeclarationResolver,
    UNRESOLVED_MODEL,
    contextModel,
    declarationModel,
    describeModel,
    getModelShape,
    type ModelIdentity,
    type TypeInference
} from "../features/transformationDeclarations.js";

const { AstUtils } = sharedImport("langium");

/**
 * The methods of a `List<model>` that may be called: they read the number of models only.
 */
const MODEL_LIST_METHODS: ReadonlySet<string> = new Set(["size", "isEmpty", "notEmpty"]);

/**
 * Validates model inputs (`in donor: model`, `in donors: List<model>`) and the scope blocks of
 * patterns that read them (`in donor { … }`).
 *
 * The rules:
 * - `model` is only written as the type of an `in` declaration (`model` or `List<model>`); the
 *   declaration rules (no `?`, no default, no `Set<model>`, no outputs) are checked with the
 *   other declarations ({@link TransformationCallValidator});
 * - a model value (a `model` input or the variable of a loop over a `List<model>` input) is only
 *   the target of a scope block, an argument of a call, the collection of a `for … in` loop
 *   (`List<model>`) or the receiver of `size()`, `isEmpty()`, `notEmpty()` (`List<model>`);
 * - the target of a scope block is a model value; scope blocks do not nest;
 * - objects of an input model are read-only: no `create` / `delete`, no `delete name`, no
 *   property assignment, no created or deleted links inside a scope block;
 * - a node of a pattern is in the model of its context: the objects of an input model are nodes
 *   only inside a scope block of that model, the objects of the transformed model only outside
 *   of scope blocks; a link never connects objects of different models;
 * - an object of an input model is never passed to a call, assigned to an output, added to an
 *   output collection, or the source of the elements of a `for … in` loop: element parameters
 *   and outputs always denote objects of the transformed model.
 */
export class ModelInputValidator {
    private readonly reflection: AstReflection;
    private readonly declarations: TransformationDeclarationResolver;

    constructor(services: ExtendedLangiumServices) {
        this.reflection = services.shared.AstReflection;
        this.declarations = new TransformationDeclarationResolver(
            this.reflection,
            services.shared.workspace.LangiumDocuments,
            () => (services as { typir?: { Inference: TypeInference } }).typir?.Inference
        );
    }

    /**
     * Validates the uses of `model` types, model values and objects of input models in the
     * whole transformation.
     *
     * @param transformation The transformation root
     * @param accept The validation acceptor
     */
    validateTransformation(transformation: ModelTransformationType, accept: ValidationAcceptor): void {
        const modelValues = new Map<string, AstNode>();
        const donorObjects = new Map<string, PatternObjectInstanceType>();
        let mentionsModel = false;

        for (const parameter of transformation.parameters ?? []) {
            if (getModelShape(this.reflection, parameter) != undefined) {
                modelValues.set(parameter.name, parameter);
            }
        }
        for (const node of AstUtils.streamAllContents(transformation)) {
            if (this.reflection.isInstance(node, ForInStatement)) {
                const variable = (node as ForInStatementType).variable;
                if (variable?.name != undefined && this.declarations.isModelLoopVariable(variable)) {
                    modelValues.set(variable.name, variable);
                }
            } else if (this.reflection.isInstance(node, PatternObjectInstance)) {
                const object = node as PatternObjectInstanceType;
                if (object.name != undefined && declarationModel(this.reflection, object) != undefined) {
                    donorObjects.set(object.name, object);
                }
            } else if (
                this.reflection.isInstance(node, typeTypes.classTypeType) &&
                (node as { name?: string }).name === "model"
            ) {
                mentionsModel = true;
            }
        }

        if (mentionsModel) {
            this.validateModelTypePositions(transformation, accept);
        }
        if (modelValues.size === 0 && donorObjects.size === 0) {
            return;
        }
        for (const node of AstUtils.streamAllContents(transformation)) {
            if (!this.reflection.isInstance(node, expressionTypes.identifierExpressionType)) {
                continue;
            }
            const name = (node as { name?: string }).name;
            if (name == undefined || this.isLambdaParameter(node, name)) {
                continue;
            }
            const modelValue = modelValues.get(name);
            if (modelValue != undefined) {
                this.validateModelValueUse(node, name, modelValue, accept);
                continue;
            }
            const donor = donorObjects.get(name);
            if (donor != undefined) {
                this.validateDonorObjectUse(node, donor, accept);
            }
        }
    }

    /**
     * Validates a scope block: its target is a model value, it is not nested in another scope
     * block, and it changes nothing.
     *
     * @param scope The scope block
     * @param accept The validation acceptor
     */
    validateModelScope(scope: PatternModelScopeType, accept: ValidationAcceptor): void {
        const target = scope.model?.ref;
        if (target != undefined && !this.declarations.isModelValue(target)) {
            const isModelList =
                this.reflection.isInstance(target, TransformationParameter) &&
                getModelShape(this.reflection, target as TransformationParameterType) === "list";
            accept(
                "error",
                isModelList
                    ? `'${scope.model.$refText}' is a list of models. Loop over it ('for m in ${scope.model.$refText} do { … }') and use 'in m { … }'.`
                    : `'${scope.model.$refText}' is not a model: a scope block 'in name { … }' needs a 'model' input or the variable of a loop over a 'List<model>' input.`,
                { node: scope, property: "model" }
            );
        }
        const outer = AstUtils.getContainerOfType(scope.$container, (n) =>
            this.reflection.isInstance(n, PatternModelScope)
        );
        if (outer != undefined) {
            accept(
                "error",
                `Scope blocks cannot be nested: this block is inside 'in ${(outer as PatternModelScopeType).model?.$refText} { … }'.`,
                {
                    node: scope,
                    property: "model"
                }
            );
        }
        if ((scope.elements ?? []).length === 0) {
            accept("warning", `The scope block 'in ${scope.model?.$refText} { }' is empty.`, { node: scope });
        }
        this.validateReadOnly(scope.elements ?? [], scope, accept);
    }

    /**
     * Validates that the elements of a scope block do not change the input model: no modifiers,
     * no `delete name`, no property assignments. Elements of a nested condition block are checked
     * by the condition block itself.
     *
     * @param elements The block's elements
     * @param scope The scope block
     * @param accept The validation acceptor
     */
    private validateReadOnly(
        elements: readonly AstNode[],
        scope: PatternModelScopeType,
        accept: ValidationAcceptor
    ): void {
        const model = `'${scope.model?.$refText ?? "?"}'`;
        for (const element of elements) {
            if (
                this.reflection.isInstance(element, PatternObjectInstance) ||
                this.reflection.isInstance(element, PatternLink)
            ) {
                const modifier = (element as { modifier?: { modifier?: string } }).modifier?.modifier;
                if (modifier != undefined) {
                    accept(
                        "error",
                        `The input model ${model} is read-only: '${modifier}' cannot be used inside its scope block. Create or delete in the transformed model, outside the block.`,
                        { node: element, property: "modifier" }
                    );
                }
            }
            if (
                this.reflection.isInstance(element, PatternObjectInstance) ||
                this.reflection.isInstance(element, PatternObjectInstanceReference)
            ) {
                for (const property of (element as { properties?: PatternPropertyAssignmentType[] }).properties ?? []) {
                    if (property.operator === "=") {
                        accept(
                            "error",
                            `The input model ${model} is read-only: a property cannot be assigned inside its scope block. Use a comparison operator (==, !=, <, >, <=, >=), or read the value in an assignment outside the block.`,
                            { node: property, property: "operator" }
                        );
                    }
                }
            }
            if (this.reflection.isInstance(element, PatternObjectInstanceDelete)) {
                accept("error", `The input model ${model} is read-only: its objects cannot be deleted.`, {
                    node: element
                });
            }
        }
    }

    /**
     * Validates that a node reference (`name { }` or `delete name`) is in the model of its
     * context.
     *
     * @param reference The reference
     * @param accept The validation acceptor
     */
    validateNodeReference(
        reference: PatternObjectInstanceReferenceType | PatternObjectInstanceDeleteType,
        accept: ValidationAcceptor
    ): void {
        const declaration = reference.instance?.ref;
        if (declaration == undefined) {
            return;
        }
        this.checkNodeModel(reference, "instance", declaration, contextModel(this.reflection, reference), accept);
    }

    /**
     * Validates that a link connects objects of one model, the model of its context.
     *
     * @param link The link
     * @param accept The validation acceptor
     */
    validateLink(link: PatternLinkType, accept: ValidationAcceptor): void {
        const source = link.source?.object?.ref;
        const target = link.target?.object?.ref;
        if (source == undefined || target == undefined) {
            return;
        }
        const sourceModel = declarationModel(this.reflection, source);
        const targetModel = declarationModel(this.reflection, target);
        if (sourceModel === UNRESOLVED_MODEL || targetModel === UNRESOLVED_MODEL) {
            return;
        }
        if (sourceModel !== targetModel) {
            accept(
                "error",
                `A link cannot connect objects of different models: '${link.source.object.$refText}' is an object of ${describeModel(sourceModel)}, '${link.target.object.$refText}' of ${describeModel(targetModel)}.`,
                { node: link }
            );
            return;
        }
        const context = contextModel(this.reflection, link);
        this.checkNodeModel(link.source, "object", source, context, accept);
        this.checkNodeModel(link.target, "object", target, context, accept);
    }

    /**
     * Reports a node of a pattern whose object is not in the model of the node's context.
     */
    private checkNodeModel(
        node: AstNode,
        property: string,
        declaration: AstNode,
        context: ModelIdentity,
        accept: ValidationAcceptor
    ): void {
        const model = declarationModel(this.reflection, declaration);
        if (model === UNRESOLVED_MODEL || context === UNRESOLVED_MODEL || model === context) {
            return;
        }
        const name = (declaration as { name?: string }).name ?? "?";
        const message =
            model != undefined
                ? `'${name}' is an object of ${describeModel(model)}; it is a node of a pattern only inside a scope block 'in ${(model as { name?: string }).name} { … }'. Elsewhere it can only be read in expressions.`
                : `'${name}' is an object of the transformed model; inside the scope block of ${describeModel(context)} only objects of that model are nodes.`;
        accept("error", message, { node, property });
    }

    /**
     * Validates a use of a model value (or of a `List<model>` input) in an expression.
     */
    private validateModelValueUse(node: AstNode, name: string, declaration: AstNode, accept: ValidationAcceptor): void {
        const parent = node.$container;
        if (this.reflection.isInstance(parent, CallArgument) && (parent as CallArgumentType).value === node) {
            return;
        }
        const isList =
            this.reflection.isInstance(declaration, TransformationParameter) &&
            getModelShape(this.reflection, declaration as TransformationParameterType) === "list";
        if (isList) {
            if (
                this.reflection.isInstance(parent, ForInStatement) &&
                (parent as ForInStatementType).collection === node
            ) {
                return;
            }
            if (
                this.reflection.isInstance(parent, expressionTypes.memberCallExpressionType) &&
                (parent as { expression?: AstNode }).expression === node &&
                MODEL_LIST_METHODS.has((parent as { member?: string }).member ?? "")
            ) {
                return;
            }
        }
        accept(
            "error",
            isList
                ? `'${name}' is a list of models: it can only be passed to a call, looped over ('for m in ${name} do { … }'), or asked for size(), isEmpty() or notEmpty().`
                : `'${name}' is a model: it can only be the target of a scope block ('in ${name} { … }') or passed to a call.`,
            { node }
        );
    }

    /**
     * Validates a use of an object of an input model in an expression: it may be read, but not
     * passed on as an element of the transformed model.
     */
    private validateDonorObjectUse(node: AstNode, donor: PatternObjectInstanceType, accept: ValidationAcceptor): void {
        const parent = node.$container;
        const model = describeModel(declarationModel(this.reflection, donor));
        const fail = (what: string) =>
            accept(
                "error",
                `'${donor.name}' is an object of ${model}, so it cannot be ${what}: element inputs and outputs always denote objects of the transformed model. Read its properties instead.`,
                { node }
            );
        if (this.reflection.isInstance(parent, CallArgument) && (parent as CallArgumentType).value === node) {
            fail(`passed to '${(parent as CallArgumentType).parameter?.$refText}'`);
            return;
        }
        if (
            (this.reflection.isInstance(parent, PatternVariableReassignment) ||
                this.reflection.isInstance(parent, AssignmentStatement) ||
                this.reflection.isInstance(parent, PatternCollectionAppend)) &&
            (parent as { value?: AstNode }).value === node &&
            this.reflection.isInstance(
                (parent as { variable?: { ref?: AstNode } }).variable?.ref,
                TransformationParameter
            )
        ) {
            fail(`assigned to the output '${(parent as { variable?: { $refText?: string } }).variable?.$refText}'`);
            return;
        }
        const loop = AstUtils.getContainerOfType(node, (n) => this.reflection.isInstance(n, ForInStatement)) as
            ForInStatementType | undefined;
        if (
            loop != undefined &&
            loop.collection != undefined &&
            (loop.collection === node || AstUtils.hasContainerOfType(node, (n) => n === loop.collection)) &&
            loop.variable != undefined &&
            this.declarations.isElementLoopVariable(loop.variable)
        ) {
            fail(`the source of the elements of the loop over '${loop.variable.name}'`);
        }
    }

    /**
     * Reports every `model` written where it is not the type of an `in` declaration (local
     * variables, casts, type checks, lambda types, …).
     */
    private validateModelTypePositions(transformation: ModelTransformationType, accept: ValidationAcceptor): void {
        for (const node of AstUtils.streamAllContents(transformation)) {
            if (
                !this.reflection.isInstance(node, typeTypes.classTypeType) ||
                (node as { name?: string }).name !== "model"
            ) {
                continue;
            }
            // declarations are checked with the other declarations
            if (AstUtils.hasContainerOfType(node, (n) => this.reflection.isInstance(n, TransformationParameter))) {
                continue;
            }
            accept(
                "error",
                `The type 'model' can only be the type of an input ('in name: model' or 'in name: List<model>'); model values cannot be stored in variables.`,
                { node }
            );
        }
    }

    /**
     * Whether a name in an expression is a parameter of an enclosing lambda (which shadows
     * every other declaration of that name).
     */
    private isLambdaParameter(node: AstNode, name: string): boolean {
        for (let current = node.$container; current != undefined; current = current.$container) {
            if (
                this.reflection.isInstance(current, LambdaExpression) &&
                ((current as LambdaExpressionType).parameterList?.parameters ?? []).some((p) => p.name === name)
            ) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates an application condition block that is nested in another one (through a scope
     * block): a condition is a plain graph, it does not have conditions of its own.
     *
     * @param condition The condition block
     * @param accept The validation acceptor
     */
    validateConditionNotNested(condition: AstNode, accept: ValidationAcceptor): void {
        if (
            AstUtils.hasContainerOfType(condition.$container, (n) =>
                this.reflection.isInstance(n, PatternApplicationCondition)
            )
        ) {
            accept("error", `A 'forbid' / 'require' block cannot be nested in another one.`, {
                node: condition,
                property: "kind"
            });
        }
    }
}
