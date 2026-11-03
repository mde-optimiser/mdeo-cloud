import type { AstReflection } from "@mdeo/language-common";
import { getExportedEntitiesByPath, type ClassType, type EnumType } from "@mdeo/language-metamodel";
import { resolveRelativePath, sharedImport } from "@mdeo/language-shared";
import type { AstNode, LangiumDocuments } from "langium";
import {
    CallOutput,
    ForInVariable,
    ForMatchStatement,
    IfMatchStatement,
    MatchStatement,
    ModelTransformation,
    PatternObjectInstance,
    PatternModelScope,
    TransformationParameter,
    WhileMatchStatement,
    typeTypes,
    type CallOutputType,
    type ForInStatementType,
    type ForInVariableType,
    type IfMatchStatementType,
    type ModelTransformationType,
    type PatternModelScopeType,
    type PatternObjectInstanceType,
    type PatternType,
    type TransformationParameterType
} from "../grammar/modelTransformationTypes.js";

const { AstUtils, UriUtils } = sharedImport("langium");

/**
 * What the type of a transformation parameter denotes.
 *
 * - `element`: a class of the metamodel, so the parameter is a model element (a node of the
 *   model graph) that patterns can reference as `name { }`;
 * - `scalar`: a primitive type or an enum of the metamodel, so the parameter is a value;
 * - `collection`: a `List<T>` or `Set<T>` of model elements or of scalars;
 * - `model`: `model` or `List<model>`, another model (or several) of the transformation's
 *   metamodel that scope blocks (`in name { … }`) read, see {@link getModelShape};
 * - `unsupported`: anything else (other generic types, lambdas, `Any`).
 */
export type ParameterKind = "element" | "scalar" | "collection" | "model" | "unsupported";

/**
 * The name of the built-in type of model values (`in donor: model`, `in donors: List<model>`).
 */
export const MODEL_TYPE_NAME = "model";

/**
 * How a parameter typed with `model` holds models: one (`model`) or any number
 * (`List<model>`).
 */
export type ModelShape = "single" | "list";

/**
 * The collection types a collection parameter may have.
 */
export const COLLECTION_PARAMETER_TYPES: ReadonlySet<string> = new Set(["List", "Set"]);

/**
 * What a collection parameter holds.
 */
export interface CollectionShape {
    /**
     * `List` (ordered, duplicates allowed) or `Set` (no duplicates).
     */
    collection: "List" | "Set";
    /**
     * Whether the elements are model elements or scalars.
     */
    elementKind: "element" | "scalar";
    /**
     * The class of model elements.
     */
    elementClass?: ClassType;
}

/**
 * The part of the Typir services the resolver uses to find the element type of the collection
 * a `for … in` loop iterates over.
 */
export interface TypeInference {
    inferType(node: AstNode): unknown;
}

/**
 * The primitive types a scalar parameter may have.
 */
export const SCALAR_PRIMITIVE_TYPES: ReadonlySet<string> = new Set([
    "int",
    "long",
    "float",
    "double",
    "string",
    "boolean"
]);

/**
 * A parameter's type, as written: its name and whether it is nullable.
 */
export interface ParameterTypeName {
    name: string;
    isNullable: boolean;
    hasTypeArgs: boolean;
    /**
     * The type arguments as written, e.g. `Component` of `List<Component>`.
     */
    typeArgs: ParameterTypeName[];
}

/**
 * Reads the name of a parameter's type, if it is a plain class type (`Name`, `Name?`,
 * `pkg.Name`). Lambda types yield `undefined`.
 *
 * @param reflection The AST reflection
 * @param parameter The parameter
 * @returns The type name, or undefined for other types
 */
export function parameterTypeName(
    reflection: AstReflection,
    parameter: TransformationParameterType
): ParameterTypeName | undefined {
    return typeNameOf(reflection, parameter.type as AstNode | undefined);
}

/**
 * Reads the name of a written type (`Name`, `Name?`, `List<Name>`).
 *
 * @param reflection The AST reflection
 * @param type The type node
 * @returns The type name, or undefined for lambda types
 */
function typeNameOf(reflection: AstReflection, type: AstNode | undefined): ParameterTypeName | undefined {
    if (type == undefined || !reflection.isInstance(type, typeTypes.classTypeType)) {
        return undefined;
    }
    const classType = type as unknown as { name?: string; isNullable?: boolean; typeArgs?: AstNode[] };
    if (classType.name == undefined) {
        return undefined;
    }
    const typeArgs = (classType.typeArgs ?? [])
        .map((arg) => typeNameOf(reflection, arg))
        .filter((arg): arg is ParameterTypeName => arg != undefined);
    return {
        name: classType.name,
        isNullable: classType.isNullable === true,
        hasTypeArgs: (classType.typeArgs?.length ?? 0) > 0,
        typeArgs
    };
}

/**
 * Resolves what the instances, parameters and call outputs of a transformation denote: which
 * metamodel class an object declaration has, and whether a parameter is a model element or a
 * scalar.
 *
 * Parameter types are written with the expression language's type syntax (`AllocationContext`,
 * `int`, `Kind?`); a type names a model element when its name is a class of the transformation's
 * metamodel (including the metamodels it imports), exactly as the class of a pattern object is
 * resolved.
 */
export class TransformationDeclarationResolver {
    /**
     * @param reflection The AST reflection
     * @param documents The Langium documents, for the metamodels of the transformations
     * @param inference Returns the Typir inference, used for the element class of `for … in`
     *   loops; without it a loop variable has no class
     */
    constructor(
        private readonly reflection: AstReflection,
        private readonly documents: LangiumDocuments,
        private readonly inference?: () => TypeInference | undefined
    ) {}

    /**
     * Returns the class of an object declaration: the class of a pattern object, the class an
     * element parameter is typed with, the class of the callee parameter a call output binds, or
     * the element class of the collection a `for … in` loop variable iterates over.
     *
     * @param declaration The declaration (may be undefined)
     * @returns The class, or undefined when there is none
     */
    getDeclaredClass(declaration: AstNode | undefined): ClassType | undefined {
        if (declaration == undefined) {
            return undefined;
        }
        if (this.reflection.isInstance(declaration, PatternObjectInstance)) {
            return (declaration as PatternObjectInstanceType).class?.ref as ClassType | undefined;
        }
        if (this.reflection.isInstance(declaration, TransformationParameter)) {
            return this.getParameterClass(declaration as TransformationParameterType);
        }
        if (this.reflection.isInstance(declaration, CallOutput)) {
            const parameter = (declaration as CallOutputType).parameter?.ref;
            return parameter != undefined ? this.getParameterClass(parameter) : undefined;
        }
        if (this.reflection.isInstance(declaration, ForInVariable)) {
            return this.getLoopElementClass(declaration as ForInVariableType);
        }
        return undefined;
    }

    /**
     * Whether the variable of a `for … in` loop is a model value: the loop runs over a
     * `List<model>`. Without type inference, a loop directly over a `List<model>` parameter
     * still counts.
     *
     * @param variable The loop variable
     * @returns True when the loop runs over models
     */
    isModelLoopVariable(variable: ForInVariableType): boolean {
        const statement = variable.$container as ForInStatementType | undefined;
        const collection = statement?.collection as { $type?: string; name?: string } | undefined;
        if (collection == undefined) {
            return false;
        }
        const inference = this.inference?.();
        if (inference != undefined) {
            try {
                const type = inference.inferType(statement!.collection);
                if (!Array.isArray(type)) {
                    const definition = (type as { definition?: { typeArgs?: Record<string, unknown> } } | undefined)
                        ?.definition;
                    const element = definition?.typeArgs?.["T"] as { package?: string; type?: string } | undefined;
                    if (element != undefined) {
                        return element.package === "builtin" && element.type === MODEL_TYPE_NAME;
                    }
                }
            } catch {
                // fall back to the declaration below
            }
        }
        if (collection.name == undefined) {
            return false;
        }
        const root = getTransformationRoot(this.reflection, variable);
        const parameter = (root?.parameters ?? []).find((p) => p.name === collection.name);
        return parameter != undefined && getModelShape(this.reflection, parameter) === "list";
    }

    /**
     * Whether a declaration is a model value: a `model` input or the variable of a loop over a
     * `List<model>` input. A `List<model>` input itself is not a model value (it holds models).
     *
     * @param declaration The declaration
     * @returns True for model values
     */
    isModelValue(declaration: AstNode | undefined): boolean {
        if (this.reflection.isInstance(declaration, TransformationParameter)) {
            return getModelShape(this.reflection, declaration as TransformationParameterType) === "single";
        }
        if (this.reflection.isInstance(declaration, ForInVariable)) {
            return this.isModelLoopVariable(declaration as ForInVariableType);
        }
        return false;
    }

    /**
     * Returns the class of the elements a `for … in` loop iterates over, from the type of its
     * collection expression (`List<T>`, `Set<T>` or any other collection of a class `T`).
     *
     * @param variable The loop variable
     * @returns The class, or undefined for collections of scalars and untyped collections
     */
    getLoopElementClass(variable: ForInVariableType): ClassType | undefined {
        const statement = variable.$container as ForInStatementType | undefined;
        const inference = this.inference?.();
        if (statement?.collection == undefined || inference == undefined) {
            return undefined;
        }
        let type: unknown;
        try {
            type = inference.inferType(statement.collection);
        } catch {
            return undefined;
        }
        if (Array.isArray(type)) {
            return undefined;
        }
        const definition = (type as { definition?: { typeArgs?: Record<string, unknown> } } | undefined)?.definition;
        const element = definition?.typeArgs?.["T"] as { package?: string; type?: string } | undefined;
        if (element?.package == undefined || element.type == undefined || !element.package.startsWith("class")) {
            return undefined;
        }
        return this.findClass(variable, element.type, element.package.slice("class".length));
    }

    /**
     * Whether the loop variable of a `for … in` loop is a model element.
     *
     * @param variable The loop variable
     * @returns True when the loop iterates over model elements
     */
    isElementLoopVariable(variable: ForInVariableType): boolean {
        return this.getLoopElementClass(variable) != undefined;
    }

    /**
     * Returns the shape of a collection parameter: `List` or `Set`, and whether it holds model
     * elements or scalars.
     *
     * @param parameter The parameter
     * @returns The shape, or undefined when the parameter is not a supported collection
     */
    getCollectionShape(parameter: TransformationParameterType): CollectionShape | undefined {
        const typeName = parameterTypeName(this.reflection, parameter);
        if (
            typeName == undefined ||
            !COLLECTION_PARAMETER_TYPES.has(typeName.name) ||
            typeName.typeArgs.length !== 1 ||
            typeName.isNullable
        ) {
            return undefined;
        }
        const element = typeName.typeArgs[0];
        if (element.hasTypeArgs || element.isNullable) {
            return undefined;
        }
        const collection = typeName.name as "List" | "Set";
        const entity = this.resolveTypeName(parameter, element.name);
        if (entity?.kind === "class") {
            return { collection, elementKind: "element", elementClass: entity.node };
        }
        if (entity?.kind === "enum" || SCALAR_PRIMITIVE_TYPES.has(element.name)) {
            return { collection, elementKind: "scalar" };
        }
        return undefined;
    }

    /**
     * Returns the pattern object an optional element input pins (proposal C, story diagrams'
     * maybe-bound objects): a nullable element input whose name is that of an object of the
     * transformation's first pattern. When the caller passes the input, the object is bound
     * to it; when not (or when it passes null), the object is matched freely.
     *
     * The first pattern is the pattern of the first statement when that is a `match`,
     * `if match`, `for match` or `while match`.
     *
     * @param parameter The parameter
     * @returns The pinned object, or undefined when the parameter pins none
     */
    getPinnedObject(parameter: TransformationParameterType): PatternObjectInstanceType | undefined {
        if (parameter.direction !== "in" || parameterTypeName(this.reflection, parameter)?.isNullable !== true) {
            return undefined;
        }
        if (this.getParameterKind(parameter) !== "element") {
            return undefined;
        }
        return this.firstPatternObjects(parameter).find((object) => object.name === parameter.name);
    }

    /**
     * Returns the objects declared by the first pattern of the transformation containing a node
     * (outside its `forbid` / `require` blocks).
     *
     * @param node Any node of the transformation
     * @returns The objects of the first pattern
     */
    firstPatternObjects(node: AstNode): PatternObjectInstanceType[] {
        const pattern = this.firstPattern(node);
        return (pattern?.elements ?? []).filter((element): element is PatternObjectInstanceType =>
            this.reflection.isInstance(element, PatternObjectInstance)
        );
    }

    /**
     * Returns the first pattern of the transformation containing a node: the pattern of its first
     * statement, when that is a `match`, `if match`, `for match` or `while match`.
     *
     * @param node Any node of the transformation
     * @returns The pattern, or undefined
     */
    firstPattern(node: AstNode): PatternType | undefined {
        const root = getTransformationRoot(this.reflection, node);
        const first = root?.statements?.[0];
        if (first == undefined) {
            return undefined;
        }
        if (this.reflection.isInstance(first, IfMatchStatement)) {
            return (first as IfMatchStatementType).ifBlock?.pattern;
        }
        if (
            this.reflection.isInstance(first, MatchStatement) ||
            this.reflection.isInstance(first, ForMatchStatement) ||
            this.reflection.isInstance(first, WhileMatchStatement)
        ) {
            return (first as { pattern?: PatternType }).pattern;
        }
        return undefined;
    }

    /**
     * Returns the class an element parameter is typed with.
     *
     * @param parameter The parameter
     * @returns The class, or undefined when the parameter is not typed with a class
     */
    getParameterClass(parameter: TransformationParameterType): ClassType | undefined {
        const entity = this.resolveParameterType(parameter);
        return entity?.kind === "class" ? entity.node : undefined;
    }

    /**
     * Classifies a parameter by its type.
     *
     * @param parameter The parameter
     * @returns Whether it is an element, a scalar, or not supported
     */
    getParameterKind(parameter: TransformationParameterType): ParameterKind {
        const typeName = parameterTypeName(this.reflection, parameter);
        if (typeName == undefined) {
            return "unsupported";
        }
        if (getModelShape(this.reflection, parameter) != undefined) {
            return "model";
        }
        if (typeName.hasTypeArgs) {
            return this.getCollectionShape(parameter) != undefined ? "collection" : "unsupported";
        }
        const entity = this.resolveParameterType(parameter);
        if (entity?.kind === "class") {
            return "element";
        }
        if (entity?.kind === "enum" || SCALAR_PRIMITIVE_TYPES.has(typeName.name)) {
            return "scalar";
        }
        return "unsupported";
    }

    /**
     * Whether a parameter of scalar kind is typed with an enum of the metamodel (rather than a
     * primitive type); for a collection, whether its elements are.
     *
     * @param parameter The parameter
     * @returns True for enums and collections of enums
     */
    isEnumTyped(parameter: TransformationParameterType): boolean {
        const typeName = parameterTypeName(this.reflection, parameter);
        if (typeName == undefined) {
            return false;
        }
        const name = typeName.hasTypeArgs ? typeName.typeArgs[0]?.name : typeName.name;
        return name != undefined && this.resolveTypeName(parameter, name)?.kind === "enum";
    }

    /**
     * Whether a declaration is an element whose value may be null: an element parameter or call
     * output typed `T?`. Such a name cannot be a node of a pattern.
     *
     * @param declaration The declaration
     * @returns True for nullable element parameters and outputs
     */
    isNullableElement(declaration: AstNode | undefined): boolean {
        let parameter: TransformationParameterType | undefined;
        if (this.reflection.isInstance(declaration, TransformationParameter)) {
            parameter = declaration as TransformationParameterType;
        } else if (this.reflection.isInstance(declaration, CallOutput)) {
            parameter = (declaration as CallOutputType).parameter?.ref;
        }
        if (parameter == undefined) {
            return false;
        }
        return parameterTypeName(this.reflection, parameter)?.isNullable === true;
    }

    /**
     * Returns the metamodel document of the transformation containing a node.
     *
     * @param node Any node of a transformation
     * @returns The metamodel document, if it is loaded
     */
    getMetamodelDocument(node: AstNode) {
        const root = AstUtils.getContainerOfType(node, (n) => this.reflection.isInstance(n, ModelTransformation)) as
            ModelTransformationType | undefined;
        const file = root?.import?.file;
        if (root == undefined || file == undefined) {
            return undefined;
        }
        const document = AstUtils.getDocument(root);
        return this.documents.getDocument(resolveRelativePath(document, file));
    }

    /**
     * Resolves the type name of a parameter against the classes and enums of the
     * transformation's metamodel.
     *
     * @param parameter The parameter
     * @returns The class or enum, if the name denotes one
     */
    private resolveParameterType(
        parameter: TransformationParameterType
    ): { kind: "class"; node: ClassType } | { kind: "enum"; node: EnumType } | undefined {
        const typeName = parameterTypeName(this.reflection, parameter);
        if (typeName == undefined) {
            return undefined;
        }
        return this.resolveTypeName(parameter, typeName.name);
    }

    /**
     * Resolves a type name against the classes and enums of the metamodel of the transformation
     * containing a node.
     *
     * @param node Any node of the transformation
     * @param name The type name
     * @returns The class or enum, if the name denotes one
     */
    private resolveTypeName(
        node: AstNode,
        name: string
    ): { kind: "class"; node: ClassType } | { kind: "enum"; node: EnumType } | undefined {
        const metamodelDocument = this.getMetamodelDocument(node);
        if (metamodelDocument == undefined) {
            return undefined;
        }
        for (const entities of getExportedEntitiesByPath(metamodelDocument, this.documents)) {
            const cls = entities.classes.find((c) => c.name === name);
            if (cls != undefined) {
                return { kind: "class", node: cls };
            }
            const enm = entities.enums.find((e) => e.name === name);
            if (enm != undefined) {
                return { kind: "enum", node: enm };
            }
        }
        return undefined;
    }

    /**
     * Finds a class of the metamodel of the transformation containing a node, by its name and
     * the path of the metamodel file declaring it.
     *
     * @param node Any node of the transformation
     * @param name The class name
     * @param path The absolute path of the declaring metamodel file
     * @returns The class, if there is one
     */
    private findClass(node: AstNode, name: string, path: string): ClassType | undefined {
        const metamodelDocument = this.getMetamodelDocument(node);
        if (metamodelDocument == undefined) {
            return undefined;
        }
        const byPath = getExportedEntitiesByPath(metamodelDocument, this.documents);
        const exact = byPath.find((entities) => entities.absolutePath === path)?.classes.find((c) => c.name === name);
        return exact ?? byPath.flatMap((entities) => entities.classes).find((c) => c.name === name);
    }
}

/**
 * Returns a declaration as a pattern object instance, if it is one.
 *
 * @param reflection The AST reflection
 * @param declaration The declaration
 * @returns The pattern object instance, or undefined for parameters and call outputs
 */
export function asPatternObjectInstance(
    reflection: AstReflection,
    declaration: AstNode | undefined
): PatternObjectInstanceType | undefined {
    return reflection.isInstance(declaration, PatternObjectInstance)
        ? (declaration as PatternObjectInstanceType)
        : undefined;
}

/**
 * Returns the transformation root of a node.
 *
 * @param reflection The AST reflection
 * @param node Any node
 * @returns The root, if the node belongs to a transformation
 */
export function getTransformationRoot(reflection: AstReflection, node: AstNode): ModelTransformationType | undefined {
    return AstUtils.getContainerOfType(node, (n) => reflection.isInstance(n, ModelTransformation)) as
        ModelTransformationType | undefined;
}

/**
 * Lists the inputs of a transformation that callers must pass: the `in` declarations without a
 * default value whose type is not nullable (model inputs included: they never have a default).
 * A nullable input without a default is optional: it is null when not passed (for an element
 * input that pins an object of the first pattern, the object is then matched freely).
 *
 * Works on a transformation loaded as an external AST as well (it only reads the declarations).
 *
 * @param transformation The transformation root (may be undefined)
 * @returns The required input parameters
 */
export function requiredInputs(transformation: ModelTransformationType | undefined): TransformationParameterType[] {
    return (transformation?.parameters ?? []).filter(
        (p) => p.direction === "in" && p.defaultValue == undefined && !isNullableType(p)
    );
}

/**
 * Whether a parameter's type is written nullable (`T?`).
 *
 * @param parameter The parameter
 * @returns True for a nullable type
 */
function isNullableType(parameter: TransformationParameterType): boolean {
    return (parameter.type as { isNullable?: boolean } | undefined)?.isNullable === true;
}

/**
 * Returns how a parameter holds models, from its type as written: `model` (or `model?`, which
 * the validator rejects) holds one, `List<model>` any number. Other types, `Set<model>` and
 * nested forms included, are not model parameters.
 *
 * @param reflection The AST reflection
 * @param parameter The parameter
 * @returns The shape, or undefined when the parameter is not typed with `model`
 */
export function getModelShape(
    reflection: AstReflection,
    parameter: TransformationParameterType
): ModelShape | undefined {
    const typeName = parameterTypeName(reflection, parameter);
    if (typeName == undefined) {
        return undefined;
    }
    if (typeName.name === MODEL_TYPE_NAME && !typeName.hasTypeArgs) {
        return "single";
    }
    const element = typeName.typeArgs[0];
    if (
        typeName.name === "List" &&
        typeName.typeArgs.length === 1 &&
        element.name === MODEL_TYPE_NAME &&
        !element.hasTypeArgs
    ) {
        return "list";
    }
    return undefined;
}

/**
 * What an `in` declaration of a transformation is, as the configuration (operator arguments,
 * mutation and crossover classification) needs to know it.
 *
 * - `element`, `scalar`, `enum`: a single model element, primitive value or enum literal;
 * - `collection`: a `List<T>` / `Set<T>` of elements, scalars or enums ({@link elementKind});
 * - `model`: one input model (`model`); `modelList`: any number of them (`List<model>`);
 * - `unsupported`: a type the transformation language rejects.
 */
export type TransformationInputKind =
    "element" | "scalar" | "enum" | "collection" | "model" | "modelList" | "unsupported";

/**
 * One `in` declaration of a transformation, see {@link describeTransformationInputs}.
 */
export interface TransformationInputDescription {
    /**
     * The input's name.
     */
    name: string;
    /**
     * What the input is.
     */
    kind: TransformationInputKind;
    /**
     * For `collection`: what the elements are.
     */
    elementKind?: "element" | "scalar" | "enum";
    /**
     * For `collection` (and `modelList`): `List` or `Set`.
     */
    collection?: "List" | "Set";
    /**
     * The type as written, e.g. `List<Component>`, `int`, `Kind`, `model` (without `?`).
     */
    typeName: string;
    /**
     * The name of the type, or of the element type of a collection (e.g. `Component`, `int`,
     * `Kind`, `model`).
     */
    baseTypeName: string;
    /**
     * Whether the type is written nullable (`T?`).
     */
    nullable: boolean;
    /**
     * Whether the declaration has a default value (`= …`).
     */
    hasDefault: boolean;
    /**
     * Whether a caller (or the configuration) may leave the input out: it has a default or a
     * nullable type. The inverse of membership in {@link requiredInputs}.
     */
    optional: boolean;
    /**
     * The declaration itself.
     */
    parameter: TransformationParameterType;
}

/**
 * Describes the `in` declarations of a transformation, for the configuration language: which
 * inputs a file has, what each of them is and whether it must be supplied. Element, enum and
 * scalar kinds are told apart by resolving the type names against the transformation's metamodel
 * (and the metamodels it imports), so that metamodel must be loaded in `documents`; otherwise a
 * class or enum type comes out as `unsupported`.
 *
 * @param transformation The transformation root (may be loaded as an external AST)
 * @param reflection The AST reflection
 * @param documents The Langium documents holding the transformation's metamodel
 * @returns The inputs, in declaration order
 */
export function describeTransformationInputs(
    transformation: ModelTransformationType | undefined,
    reflection: AstReflection,
    documents: LangiumDocuments
): TransformationInputDescription[] {
    const resolver = new TransformationDeclarationResolver(reflection, documents);
    return (transformation?.parameters ?? [])
        .filter((parameter) => parameter.direction === "in")
        .map((parameter) => {
            const typeName = parameterTypeName(reflection, parameter);
            const nullable = typeName?.isNullable === true;
            const hasDefault = parameter.defaultValue != undefined;
            const base: Omit<TransformationInputDescription, "kind"> = {
                name: parameter.name,
                typeName: renderTypeName(typeName),
                baseTypeName: (typeName?.hasTypeArgs ? typeName.typeArgs[0]?.name : typeName?.name) ?? "?",
                nullable,
                hasDefault,
                optional: hasDefault || nullable,
                parameter
            };
            const modelShape = getModelShape(reflection, parameter);
            if (modelShape != undefined) {
                return modelShape === "single"
                    ? { ...base, kind: "model" as const }
                    : { ...base, kind: "modelList" as const, collection: "List" as const };
            }
            const kind = resolver.getParameterKind(parameter);
            const isEnum = resolver.isEnumTyped(parameter);
            if (kind === "collection") {
                const shape = resolver.getCollectionShape(parameter);
                return {
                    ...base,
                    kind: "collection" as const,
                    collection: shape?.collection,
                    elementKind: shape?.elementKind === "element" ? "element" : isEnum ? "enum" : "scalar"
                } satisfies TransformationInputDescription;
            }
            if (kind === "scalar") {
                return { ...base, kind: isEnum ? ("enum" as const) : ("scalar" as const) };
            }
            return { ...base, kind: kind === "element" ? ("element" as const) : ("unsupported" as const) };
        });
}

/**
 * Renders a type name as written, without the nullable marker.
 */
function renderTypeName(typeName: ParameterTypeName | undefined): string {
    if (typeName == undefined) {
        return "?";
    }
    if (typeName.typeArgs.length === 0) {
        return typeName.name;
    }
    return `${typeName.name}<${typeName.typeArgs.map((arg) => renderTypeName(arg)).join(", ")}>`;
}

/**
 * Renders the header declarations of a transformation as source text, one per line, for
 * hovers and messages.
 *
 * @param transformation The transformation root
 * @returns e.g. `in maxCandidates: int = 0\nout moved: Component`, or a note when it declares
 *   nothing
 */
export function renderDeclarations(transformation: ModelTransformationType): string {
    const lines = (transformation.parameters ?? []).map((p) => renderParameter(p));
    return lines.length > 0 ? lines.join("\n") : "// no inputs or outputs";
}

/**
 * Returns the display name of a transformation: the base name of its file (e.g.
 * `move-component.mt`), as used in diagnostics and the typed AST.
 *
 * @param transformation Any node of the transformation
 * @returns The file's base name
 */
export function transformationDisplayName(transformation: AstNode): string {
    return UriUtils.basename(AstUtils.getDocument(transformation).uri);
}

/**
 * Lists the elements of a pattern (or of a block) with the elements of its model scope blocks
 * in their place, recursively, so that what is declared in `in donor { … }` counts as declared
 * in the pattern. `forbid` / `require` blocks are kept as they are (they have their own graph).
 *
 * @param reflection The AST reflection
 * @param elements The elements of a pattern or block
 * @returns The flattened elements, in declaration order
 */
export function flattenModelScopes(reflection: AstReflection, elements: readonly unknown[]): AstNode[] {
    const result: AstNode[] = [];
    for (const element of elements) {
        if (reflection.isInstance(element, PatternModelScope)) {
            result.push(...flattenModelScopes(reflection, (element as PatternModelScopeType).elements ?? []));
        } else {
            result.push(element as AstNode);
        }
    }
    return result;
}

/**
 * Returns the innermost model scope block a node is declared in.
 *
 * @param reflection The AST reflection
 * @param node The node
 * @returns The scope block, or undefined for nodes of the implicit model
 */
export function findEnclosingModelScope(reflection: AstReflection, node: AstNode): PatternModelScopeType | undefined {
    return AstUtils.getContainerOfType(node.$container, (n) => reflection.isInstance(n, PatternModelScope)) as
        PatternModelScopeType | undefined;
}

/**
 * Stands for the model of a scope block whose target does not resolve; checks between models
 * skip it (the linking error is reported already).
 */
export const UNRESOLVED_MODEL: unique symbol = Symbol("unresolved model");

/**
 * The model an object lives in: undefined for the implicit model, the model value's declaration
 * (a `model` input or a loop variable) for an input model.
 */
export type ModelIdentity = AstNode | undefined | typeof UNRESOLVED_MODEL;

/**
 * Returns the model the context of a node is: the target of the innermost scope block containing
 * it, or the implicit model.
 *
 * @param reflection The AST reflection
 * @param node The node
 * @returns The model
 */
export function contextModel(reflection: AstReflection, node: AstNode): ModelIdentity {
    const scope = findEnclosingModelScope(reflection, node);
    if (scope == undefined) {
        return undefined;
    }
    return scope.model?.ref ?? UNRESOLVED_MODEL;
}

/**
 * Returns the model an object declaration lives in: a pattern object declared in a scope block
 * lives in the block's model; everything else (objects outside scope blocks, element
 * parameters, call outputs and loop variables over elements) in the implicit model.
 *
 * @param reflection The AST reflection
 * @param declaration The object declaration
 * @returns The model
 */
export function declarationModel(reflection: AstReflection, declaration: AstNode | undefined): ModelIdentity {
    if (declaration == undefined || !reflection.isInstance(declaration, PatternObjectInstance)) {
        return undefined;
    }
    return contextModel(reflection, declaration);
}

/**
 * Describes a model for messages.
 *
 * @param model The model
 * @returns e.g. `the input model 'donor'` or `the transformed model`
 */
export function describeModel(model: ModelIdentity): string {
    if (model == undefined) {
        return "the transformed model";
    }
    if (model === UNRESOLVED_MODEL) {
        return "an unknown model";
    }
    return `the input model '${(model as { name?: string }).name ?? "?"}'`;
}

/**
 * Renders a parameter as source text.
 *
 * @param parameter The parameter
 * @returns e.g. `in maxCandidates: int = …`
 */
export function renderParameter(parameter: TransformationParameterType): string {
    const typeText = renderTypeNode(parameter.type);
    const defaultText = parameter.defaultValue != undefined ? ` = ${parameter.defaultValue.$cstNode?.text ?? "…"}` : "";
    return `${parameter.direction} ${parameter.name}: ${typeText}${defaultText}`;
}

/**
 * Renders a type node as source text: its own text when it was parsed, otherwise from its
 * structure (a transformation loaded as an AST only, e.g. through the config's external
 * references, has no CST).
 *
 * @param type The type node
 * @returns e.g. `List<Component>` or `Placement?`; `?` without a type
 */
function renderTypeNode(type: AstNode | undefined): string {
    if (type == undefined) {
        return "?";
    }
    if (type.$cstNode != undefined) {
        return type.$cstNode.text;
    }
    const { packageName, name, typeArgs, isNullable } = type as AstNode & {
        packageName?: string;
        name?: string;
        typeArgs?: AstNode[];
        isNullable?: boolean;
    };
    if (name == undefined) {
        return "?";
    }
    const args = typeArgs != undefined && typeArgs.length > 0 ? `<${typeArgs.map(renderTypeNode).join(", ")}>` : "";
    return `${packageName != undefined ? `${packageName}.` : ""}${name}${args}${isNullable ? "?" : ""}`;
}

/**
 * The services {@link declaredClassOf} needs.
 */
export interface DeclarationServices {
    shared: {
        AstReflection: AstReflection;
        workspace: { LangiumDocuments: LangiumDocuments };
    };
    typir?: { Inference: TypeInference };
}

/**
 * Returns the class of an object declaration (pattern object, element parameter, call output or
 * loop variable), for code that has the language services at hand but no resolver of its own.
 *
 * @param services The language services
 * @param declaration The declaration
 * @returns The class, or undefined
 */
export function declaredClassOf(
    services: DeclarationServices,
    declaration: AstNode | undefined
): ClassType | undefined {
    return new TransformationDeclarationResolver(
        services.shared.AstReflection,
        services.shared.workspace.LangiumDocuments,
        () => services.typir?.Inference
    ).getDeclaredClass(declaration);
}
