import type { AstNode, LangiumDocument, ValidationAcceptor } from "langium";
import type { AstReflection, ExtendedLangiumServices } from "@mdeo/language-common";
import { isMetamodelCompatible, resolveClassChain, type ClassType } from "@mdeo/language-metamodel";
import {
    climbsAboveProjectRoot,
    resolveRelativeDocument,
    resolveRelativePath,
    sharedImport
} from "@mdeo/language-shared";
import {
    getCallee,
    AssignmentStatement,
    CallOutput,
    CallStatement,
    ForInStatement,
    ForInVariable,
    IfCallStatement,
    PatternCollectionAppend,
    ForMatchStatement,
    IfExpressionStatement,
    IfMatchStatement,
    MatchStatement,
    ModelTransformation,
    PatternObjectInstance,
    PatternVariableReassignment,
    StopStatement,
    TransformationParameter,
    UntilMatchStatement,
    WhileExpressionStatement,
    WhileMatchStatement,
    type AssignmentStatementType,
    type BaseTransformationStatementType,
    type CallStatementType,
    type ForInStatementType,
    type ForInVariableType,
    type IfCallStatementType,
    type PatternCollectionAppendType,
    type ForMatchStatementType,
    type IfExpressionStatementType,
    type IfMatchStatementType,
    type ModelTransformationType,
    type PatternObjectInstanceType,
    type PatternType,
    type PatternVariableReassignmentType,
    type StatementsScopeType,
    type StopStatementType,
    type TransformationFileImportType,
    type TransformationParameterType,
    type UntilMatchStatementType,
    type WhileExpressionStatementType,
    type WhileMatchStatementType
} from "../grammar/modelTransformationTypes.js";
import {
    TransformationDeclarationResolver,
    flattenModelScopes,
    getModelShape,
    parameterTypeName,
    requiredInputs,
    type TypeInference
} from "../features/transformationDeclarations.js";
import { CallSafetyAnalyzer } from "./callSafetyAnalyzer.js";

const { AstUtils, UriUtils } = sharedImport("langium");

/**
 * Whether a class is a given class or one of its subclasses.
 *
 * @param subclass The candidate subclass
 * @param superclass The class
 * @param reflection The AST reflection
 * @returns True if every instance of the subclass is one of the class
 */
function isSubclassOf(subclass: ClassType, superclass: ClassType, reflection: AstReflection): boolean {
    return resolveClassChain(subclass, reflection).some((c) => c === superclass || c.name === superclass.name);
}

/**
 * Validates callable transformations: header declarations, path imports of other
 * transformations, calls and the references of patterns to parameters and call outputs.
 *
 * The rules:
 * - a parameter is a single model element (a metamodel class), a scalar (`int`, `long`,
 *   `float`, `double`, `string`, `boolean`, an enum) or a `List<T>` / `Set<T>` of either; an
 *   element input has no default, and it is nullable only when it pins an object of the first
 *   pattern (it is then optional: absent or null, the object is matched freely); an `in`
 *   parameter may also be a `model` or a `List<model>` (no default, not nullable);
 * - an imported file must be a model transformation (every `.mt` file is one), whose metamodel
 *   is this file's metamodel or one this file's metamodel imports; imports must not form a
 *   cycle; importing a file twice is a warning;
 * - a call names each input at most once, passes every required input and binds only outputs;
 * - a non-nullable output is assigned on every path on which the transformation succeeds
 *   (collection outputs start empty and need no assignment);
 * - `+=` adds to a collection output only;
 * - `if call` calls only transformations that fail before they change the model.
 */
export class TransformationCallValidator {
    private readonly reflection: AstReflection;
    private readonly declarations: TransformationDeclarationResolver;
    private readonly safety: CallSafetyAnalyzer;

    constructor(private readonly services: ExtendedLangiumServices) {
        this.reflection = services.shared.AstReflection;
        this.declarations = new TransformationDeclarationResolver(
            this.reflection,
            services.shared.workspace.LangiumDocuments,
            () => (services as { typir?: { Inference: TypeInference } }).typir?.Inference
        );
        this.safety = new CallSafetyAnalyzer(this.reflection);
    }

    /**
     * Validates an `if call`: the callee must fail before it changes the model, since the engine
     * cannot undo changes and the else block would otherwise run on a partly changed model.
     *
     * @param ifCall The if-call statement
     * @param accept The validation acceptor
     */
    validateIfCall(ifCall: IfCallStatementType, accept: ValidationAcceptor): void {
        const call = ifCall.ifBlock?.call;
        const callee = getCallee(call);
        if (call == undefined || callee == undefined) {
            return;
        }
        const violation = this.safety.findUnsafeFailure(callee);
        if (violation != undefined) {
            accept(
                "error",
                `'if call' needs a transformation that fails before it changes the model, but '${call.callee?.$refText}' ${violation}. The engine does not undo changes, so the else block would run on a changed model. Use a plain 'call', or move every match that can fail before the first change.`,
                { node: call, property: "callee" }
            );
        }
    }

    /**
     * Validates the target of an assignment outside a pattern (`name = value`, `name += value`)
     * and of a `+=` inside a pattern: `+=` adds to a collection output, `=` assigns a variable
     * or an output, and neither assigns an input.
     *
     * @param node The assignment statement or pattern append
     * @param accept The validation acceptor
     */
    validateAssignmentTarget(
        node: AssignmentStatementType | PatternCollectionAppendType,
        accept: ValidationAcceptor
    ): void {
        const target = node.variable?.ref;
        if (target == undefined) {
            return;
        }
        const isAppend =
            this.reflection.isInstance(node, PatternCollectionAppend) ||
            (node as AssignmentStatementType).operator === "+=";
        const isParameter = this.reflection.isInstance(target, TransformationParameter);
        if (isParameter && (target as TransformationParameterType).direction === "in") {
            accept(
                "error",
                `Cannot assign the input parameter '${target.name}'; only 'out' parameters and variables can be assigned.`,
                { node, property: "variable" }
            );
            return;
        }
        if (
            isAppend &&
            (!isParameter || this.declarations.getParameterKind(target as TransformationParameterType) !== "collection")
        ) {
            accept(
                "error",
                `'+=' adds to a collection output; '${target.name}' is not an 'out' parameter of type List<…> or Set<…>.`,
                { node, property: "variable" }
            );
        }
    }

    /**
     * Validates the header declarations, the imports and the definite assignment of the outputs.
     *
     * @param transformation The transformation root
     * @param accept The validation acceptor
     */
    validateTransformation(transformation: ModelTransformationType, accept: ValidationAcceptor): void {
        this.validateDeclarations(transformation, accept);
        this.validateOutputsAssigned(transformation, accept);
        const imported = new Map<string, TransformationFileImportType>();
        for (const fileImport of transformation.imports ?? []) {
            this.validateImport(transformation, fileImport, accept);
            this.validateNotImportedTwice(transformation, fileImport, imported, accept);
        }
    }

    /**
     * Warns about a file that is imported a second time (under another alias): both aliases
     * name the same transformation.
     *
     * @param transformation The importing transformation
     * @param fileImport The import
     * @param imported The imports seen so far, by the resolved path of their file
     * @param accept The validation acceptor
     */
    private validateNotImportedTwice(
        transformation: ModelTransformationType,
        fileImport: TransformationFileImportType,
        imported: Map<string, TransformationFileImportType>,
        accept: ValidationAcceptor
    ): void {
        if (fileImport.file == undefined || fileImport.file.trim() === "") {
            return;
        }
        const key = resolveRelativePath(AstUtils.getDocument(transformation), fileImport.file).toString();
        const first = imported.get(key);
        if (first == undefined) {
            imported.set(key, fileImport);
            return;
        }
        accept("warning", `'${fileImport.file}' is imported already (as '${first.name}'); one import is enough.`, {
            node: fileImport,
            property: "file"
        });
    }

    /**
     * Validates a call: its arguments and outputs against the callee's declarations.
     *
     * @param call The call statement
     * @param accept The validation acceptor
     */
    validateCall(call: CallStatementType, accept: ValidationAcceptor): void {
        const callee = getCallee(call);
        if (callee == undefined) {
            return;
        }
        const calleeName = call.callee?.$refText ?? "?";

        const passed = new Set<string>();
        for (const argument of call.arguments ?? []) {
            const parameter = argument.parameter?.ref;
            if (parameter == undefined) {
                continue;
            }
            if (parameter.direction !== "in") {
                accept(
                    "error",
                    `'${parameter.name}' is an output of '${calleeName}'; bind it after '->', e.g. '-> (${parameter.name}: name)'.`,
                    { node: argument, property: "parameter" }
                );
                continue;
            }
            if (passed.has(parameter.name)) {
                accept("error", `The input '${parameter.name}' of '${calleeName}' is passed twice.`, {
                    node: argument,
                    property: "parameter"
                });
            }
            passed.add(parameter.name);
        }

        const missing = requiredInputs(callee).filter((p) => !passed.has(p.name));
        if (missing.length > 0) {
            const names = missing.map((p) => `'${p.name}'`).join(", ");
            accept("error", `Missing required input${missing.length > 1 ? "s" : ""} ${names} of '${calleeName}'.`, {
                node: call,
                property: "callee"
            });
        }

        const bound = new Set<string>();
        for (const output of call.outputs ?? []) {
            const parameter = output.parameter?.ref;
            if (parameter == undefined) {
                continue;
            }
            if (parameter.direction !== "out") {
                accept(
                    "error",
                    `'${parameter.name}' is an input of '${calleeName}'; pass it as an argument, e.g. '${parameter.name} = value'.`,
                    { node: output, property: "parameter" }
                );
                continue;
            }
            if (bound.has(parameter.name)) {
                accept("error", `The output '${parameter.name}' of '${calleeName}' is bound twice.`, {
                    node: output,
                    property: "parameter"
                });
            }
            bound.add(parameter.name);
        }
    }

    /**
     * Validates the target of a reassignment: an `out` parameter may be assigned, an `in`
     * parameter may not.
     *
     * @param reassignment The reassignment
     * @param accept The validation acceptor
     */
    validateReassignment(reassignment: PatternVariableReassignmentType, accept: ValidationAcceptor): void {
        const target = reassignment.variable?.ref;
        if (this.reflection.isInstance(target, TransformationParameter) && target.direction === "in") {
            accept(
                "error",
                `Cannot assign the input parameter '${target.name}'; only 'out' parameters and variables can be assigned.`,
                { node: reassignment, property: "variable" }
            );
        }
    }

    /**
     * Validates that a parameter or call output referenced as a node of a pattern (`name { }`,
     * `delete name`, a link end) is a model element that is bound when the pattern is matched.
     *
     * @param referenceNode The reference node, for the diagnostic
     * @param property The property holding the reference
     * @param declaration The referenced declaration
     * @param accept The validation acceptor
     * @returns True if the declaration is a parameter or call output (whether valid or not)
     */
    validateNodeReference(
        referenceNode: AstNode,
        property: string,
        declaration: AstNode,
        accept: ValidationAcceptor
    ): boolean {
        if (this.reflection.isInstance(declaration, ForInVariable)) {
            const variable = declaration as ForInVariableType;
            if (this.declarations.isModelLoopVariable(variable)) {
                accept(
                    "error",
                    `'${variable.name}' is a model, not a model element, so it cannot be a node of a pattern. Match objects of it in a scope block 'in ${variable.name} { … }'.`,
                    { node: referenceNode, property }
                );
            } else if (!this.declarations.isElementLoopVariable(variable)) {
                accept(
                    "error",
                    `'${variable.name}' runs over a collection of scalars (or of an unknown type), so it cannot be a node of a pattern. Use it in expressions instead.`,
                    { node: referenceNode, property }
                );
            }
            return true;
        }
        const isParameter = this.reflection.isInstance(declaration, TransformationParameter);
        const isOutput = this.reflection.isInstance(declaration, CallOutput);
        if (!isParameter && !isOutput) {
            return false;
        }
        const name = (declaration as { name?: string }).name ?? "";
        const parameter = isParameter
            ? (declaration as TransformationParameterType)
            : (declaration as { parameter?: { ref?: TransformationParameterType } }).parameter?.ref;
        if (parameter == undefined) {
            return true;
        }
        const kind = this.declarations.getParameterKind(parameter);
        if (kind === "model") {
            accept(
                "error",
                getModelShape(this.reflection, parameter) === "list"
                    ? `'${name}' is a list of models, not a model element, so it cannot be a node of a pattern. Loop over it with 'for m in ${name} do { … }' and match in a scope block 'in m { … }'.`
                    : `'${name}' is a model, not a model element, so it cannot be a node of a pattern. Match objects of it in a scope block 'in ${name} { … }'.`,
                { node: referenceNode, property }
            );
            return true;
        }
        if (kind === "collection") {
            accept(
                "error",
                `'${name}' is a collection, not a model element, so it cannot be a node of a pattern. Loop over it with 'for x in ${name} do { … }'.`,
                { node: referenceNode, property }
            );
            return true;
        }
        if (kind !== "element") {
            accept(
                "error",
                `'${name}' is a ${isParameter ? "scalar parameter" : "scalar output"}, not a model element, so it cannot be a node of a pattern. Use it in expressions instead.`,
                { node: referenceNode, property }
            );
            return true;
        }
        if (isParameter && parameter.direction === "out") {
            accept(
                "error",
                `'${name}' is an output parameter: it is not bound when the transformation starts. Assign it inside a pattern with '${name} = node'.`,
                { node: referenceNode, property }
            );
            return true;
        }
        if (parameterTypeName(this.reflection, parameter)?.isNullable === true) {
            if (isParameter && this.declarations.getPinnedObject(parameter) != undefined) {
                accept(
                    "error",
                    `'${name}' is only bound where the object '${name}' of the first pattern is visible: when no '${name}' is passed, that object is matched, and it is not bound here.`,
                    { node: referenceNode, property }
                );
                return true;
            }
            accept("error", `'${name}' may be null (its type is nullable), so it cannot be a node of a pattern.`, {
                node: referenceNode,
                property
            });
        }
        return true;
    }

    /**
     * Validates the header declarations (`in …` / `out …`).
     *
     * @param transformation The transformation root
     * @param accept The validation acceptor
     */
    private validateDeclarations(transformation: ModelTransformationType, accept: ValidationAcceptor): void {
        for (const parameter of transformation.parameters ?? []) {
            const kind = this.declarations.getParameterKind(parameter);
            const typeName = parameterTypeName(this.reflection, parameter);
            if (kind === "model") {
                this.validateModelParameter(parameter, accept);
                continue;
            }
            if (this.mentionsModelType(typeName)) {
                accept(
                    "error",
                    typeName?.name === "Set"
                        ? `'${parameter.name}' cannot be a Set<model>; several input models are passed as 'List<model>'.`
                        : `The type 'model' can only be written as 'in ${parameter.name}: model' or 'in ${parameter.name}: List<model>'.`,
                    { node: parameter, property: "type" }
                );
                continue;
            }
            if (kind === "unsupported") {
                accept(
                    "error",
                    typeName?.hasTypeArgs === true
                        ? `The type of '${parameter.name}' must be List<T> or Set<T> of a class or enum of the metamodel or of int, long, float, double, string or boolean, not nullable and not nested.`
                        : `The type of '${parameter.name}' must be a class or enum of the metamodel, one of int, long, float, double, string and boolean, a List<…> or Set<…> of those, or (for an input) model or List<model>.`,
                    { node: parameter, property: "type" }
                );
                continue;
            }
            if (parameter.direction === "out" && parameter.defaultValue != undefined) {
                accept("error", `Only 'in' parameters can have a default value.`, {
                    node: parameter,
                    property: "defaultValue"
                });
            }
            if (kind === "collection" && parameter.direction === "in" && parameter.defaultValue != undefined) {
                accept("error", `The collection input '${parameter.name}' cannot have a default value.`, {
                    node: parameter,
                    property: "defaultValue"
                });
            }
            if (kind === "element" && parameter.direction === "in") {
                if (typeName?.isNullable === true) {
                    this.validatePinningInput(parameter, accept);
                } else {
                    this.validateBoundInput(parameter, accept);
                }
                if (parameter.defaultValue != undefined) {
                    accept("error", `The element input '${parameter.name}' cannot have a default value.`, {
                        node: parameter,
                        property: "defaultValue"
                    });
                }
            }
        }
    }

    /**
     * Validates a parameter typed with `model`: only `in x: model` and `in xs: List<model>`
     * exist; a model input is never optional (no `?`, no default), and a transformation has no
     * model outputs (only its implicit model is changed).
     *
     * @param parameter The parameter
     * @param accept The validation acceptor
     */
    private validateModelParameter(parameter: TransformationParameterType, accept: ValidationAcceptor): void {
        const typeName = parameterTypeName(this.reflection, parameter);
        if (parameter.direction === "out") {
            accept(
                "error",
                `A transformation has no model outputs: '${parameter.name}' cannot be 'out … : ${typeName?.hasTypeArgs ? "List<model>" : "model"}'. Only the model the transformation runs on is changed.`,
                { node: parameter, property: "direction" }
            );
            return;
        }
        if (typeName?.isNullable === true || typeName?.typeArgs[0]?.isNullable === true) {
            accept(
                "error",
                `A model input cannot be nullable: '${parameter.name}' must be passed (write '${typeName?.hasTypeArgs ? "List<model>" : "model"}').`,
                { node: parameter, property: "type" }
            );
        }
        if (parameter.defaultValue != undefined) {
            accept("error", `The model input '${parameter.name}' cannot have a default value.`, {
                node: parameter,
                property: "defaultValue"
            });
        }
    }

    /**
     * Whether a written type mentions `model` anywhere.
     */
    private mentionsModelType(typeName: ReturnType<typeof parameterTypeName>): boolean {
        if (typeName == undefined) {
            return false;
        }
        return typeName.name === "model" || typeName.typeArgs.some((arg) => this.mentionsModelType(arg));
    }

    /**
     * Validates a nullable element input: it must pin an object of the first pattern with its
     * name, whose class every passed value has (the parameter's class or a superclass).
     *
     * @param parameter The parameter
     * @param accept The validation acceptor
     */
    private validatePinningInput(parameter: TransformationParameterType, accept: ValidationAcceptor): void {
        const object = this.declarations.getPinnedObject(parameter);
        if (object == undefined) {
            const sameName = this.findPatternObject(parameter);
            accept(
                "error",
                sameName != undefined
                    ? `The nullable element input '${parameter.name}' can only pin an object of the first pattern (the pattern of the first statement, a match, if match, for match or while match); '${parameter.name}' is declared in a later pattern.`
                    : `The element input '${parameter.name}' is nullable, so it must pin an object of the first pattern: declare '${parameter.name}: ${parameterTypeName(this.reflection, parameter)?.name ?? "Class"} { … }' in the pattern of the first statement. It is bound to the passed element, or matched freely when none (or null) is passed. Otherwise declare the input non-nullable.`,
                { node: parameter, property: "name" }
            );
            return;
        }
        if (object.modifier?.modifier === "create") {
            accept(
                "error",
                `The input '${parameter.name}' pins the object '${object.name}', which the pattern creates; only matched (or deleted) objects can be pinned.`,
                { node: parameter, property: "name" }
            );
            return;
        }
        const parameterClass = this.declarations.getParameterClass(parameter);
        const objectClass = object.class?.ref as ClassType | undefined;
        if (
            parameterClass != undefined &&
            objectClass != undefined &&
            !isSubclassOf(parameterClass, objectClass, this.reflection)
        ) {
            accept(
                "error",
                `The input '${parameter.name}' pins the object '${object.name}' of class '${objectClass.name}', so its type must be '${objectClass.name}' or a subclass of it ('${parameterClass.name}' is not): a pinned object is not checked against its class again.`,
                { node: parameter, property: "type" }
            );
        }
    }

    /**
     * Validates a non-nullable element input: it is already bound, so no pattern may declare an
     * object of its name (the duplicate-name check reports it; this explains what to do).
     *
     * @param parameter The parameter
     * @param accept The validation acceptor
     */
    private validateBoundInput(parameter: TransformationParameterType, accept: ValidationAcceptor): void {
        const object = this.findPatternObject(parameter);
        if (object != undefined) {
            accept(
                "error",
                `The element input '${parameter.name}' is required, so it is bound when the transformation starts; reference it as '${parameter.name} { }' instead of declaring '${parameter.name}: ${object.class?.$refText ?? "Class"}'. To match it when no '${parameter.name}' is passed, declare the input nullable ('in ${parameter.name}: ${parameterTypeName(this.reflection, parameter)?.name ?? "Class"}?').`,
                { node: object, property: "name" }
            );
        }
    }

    /**
     * Finds a pattern object (outside condition blocks) with the name of a parameter, anywhere in
     * the transformation.
     */
    private findPatternObject(parameter: TransformationParameterType): PatternObjectInstanceType | undefined {
        const root = AstUtils.getContainerOfType(parameter, (n) => this.reflection.isInstance(n, ModelTransformation));
        if (root == undefined) {
            return undefined;
        }
        for (const node of AstUtils.streamAllContents(root)) {
            if (
                this.reflection.isInstance(node, PatternObjectInstance) &&
                (node as PatternObjectInstanceType).name === parameter.name
            ) {
                return node as PatternObjectInstanceType;
            }
        }
        return undefined;
    }

    /**
     * Validates one import of other transformations: the file, the metamodel compatibility of
     * the imported transformation and the absence of import cycles.
     *
     * @param transformation The importing transformation
     * @param fileImport The import
     * @param accept The validation acceptor
     */
    private validateImport(
        transformation: ModelTransformationType,
        fileImport: TransformationFileImportType,
        accept: ValidationAcceptor
    ): void {
        const file = fileImport.file;
        if (file == undefined || file.trim() === "") {
            return;
        }
        const document = AstUtils.getDocument(transformation);
        if (climbsAboveProjectRoot(document, file)) {
            accept("error", `Import path '${file}' points outside the project.`, {
                node: fileImport,
                property: "file"
            });
            return;
        }
        const target = resolveRelativeDocument(document, file, this.services.shared.workspace.LangiumDocuments);
        if (target == undefined) {
            accept("error", `Cannot resolve import path '${file}'. The file does not exist or is not loaded.`, {
                node: fileImport,
                property: "file"
            });
            return;
        }
        const targetRoot = target.parseResult?.value;
        if (
            !file.endsWith(".mt") ||
            targetRoot == undefined ||
            !this.reflection.isInstance(targetRoot, ModelTransformation)
        ) {
            accept("error", `The path '${file}' does not point to a model transformation file.`, {
                node: fileImport,
                property: "file"
            });
            return;
        }
        const callee = targetRoot as ModelTransformationType;

        if (UriUtils.equals(target.uri, document.uri)) {
            accept("error", `A transformation cannot import itself; recursive calls are not supported.`, {
                node: fileImport,
                property: "file"
            });
            return;
        }
        const cycle = this.findImportCycle(document, target);
        if (cycle != undefined) {
            accept("error", `Import cycle: ${cycle.join(" -> ")}. Recursive calls are not supported.`, {
                node: fileImport,
                property: "file"
            });
        }

        this.validateCalleeMetamodel(transformation, document, callee, target, file, fileImport, accept);
    }

    /**
     * Validates that the imported transformation's metamodel is this transformation's
     * metamodel or one that this transformation's metamodel (transitively) imports.
     */
    private validateCalleeMetamodel(
        transformation: ModelTransformationType,
        document: LangiumDocument,
        callee: ModelTransformationType,
        calleeDocument: LangiumDocument,
        file: string,
        fileImport: TransformationFileImportType,
        accept: ValidationAcceptor
    ): void {
        const documents = this.services.shared.workspace.LangiumDocuments;
        const callerMetamodel = resolveRelativeDocument(document, transformation.import?.file, documents);
        const calleeMetamodel = resolveRelativeDocument(calleeDocument, callee.import?.file, documents);
        if (callerMetamodel == undefined || calleeMetamodel == undefined) {
            return;
        }
        if (!isMetamodelCompatible(calleeMetamodel, callerMetamodel, documents)) {
            accept(
                "error",
                `'${file}' uses the metamodel '${callee.import.file}', which is neither this transformation's metamodel nor imported by it. A transformation can only call transformations of its own metamodel or of a metamodel its metamodel imports.`,
                { node: fileImport, property: "file" }
            );
        }
    }

    /**
     * Searches the imports reachable from [start] for a path back to [origin].
     *
     * @param origin The importing document
     * @param start The imported document
     * @returns The file names of the cycle, from origin back to origin, or undefined
     */
    private findImportCycle(origin: LangiumDocument, start: LangiumDocument): string[] | undefined {
        const documents = this.services.shared.workspace.LangiumDocuments;
        const visited = new Set<string>();
        const fileName = (doc: LangiumDocument) => UriUtils.basename(doc.uri);
        const search = (doc: LangiumDocument, path: string[]): string[] | undefined => {
            if (UriUtils.equals(doc.uri, origin.uri)) {
                return path;
            }
            const key = doc.uri.toString();
            if (visited.has(key)) {
                return undefined;
            }
            visited.add(key);
            const root = doc.parseResult?.value;
            if (!this.reflection.isInstance(root, ModelTransformation)) {
                return undefined;
            }
            for (const fileImport of (root as ModelTransformationType).imports ?? []) {
                if (fileImport.file == undefined) {
                    continue;
                }
                const next = documents.getDocument(resolveRelativePath(doc, fileImport.file));
                if (next != undefined) {
                    const found = search(next, [...path, fileName(next)]);
                    if (found != undefined) {
                        return found;
                    }
                }
            }
            return undefined;
        };
        return search(start, [fileName(origin), fileName(start)]);
    }

    /**
     * Validates that every non-nullable output is assigned on every path on which the
     * transformation succeeds: at its end and at every `stop`. A path that ends in `kill` or in
     * a failing match is not a successful path.
     *
     * @param transformation The transformation root
     * @param accept The validation acceptor
     */
    private validateOutputsAssigned(transformation: ModelTransformationType, accept: ValidationAcceptor): void {
        const required = (transformation.parameters ?? []).filter(
            (p) =>
                p.direction === "out" &&
                parameterTypeName(this.reflection, p)?.isNullable !== true &&
                this.declarations.getParameterKind(p) !== "unsupported" &&
                this.declarations.getParameterKind(p) !== "collection" &&
                this.declarations.getParameterKind(p) !== "model"
        );
        if (required.length === 0) {
            return;
        }
        const stops: { stop: StopStatementType; assigned: Set<string> }[] = [];
        const atEnd = this.analyzeStatements(transformation.statements ?? [], new Set(), stops);

        const missingAt = (assigned: Set<string>) => required.filter((p) => !assigned.has(p.name));
        for (const { stop, assigned } of stops) {
            const missing = missingAt(assigned);
            if (missing.length > 0) {
                accept(
                    "error",
                    `The transformation returns here without assigning ${this.describe(missing)}. Assign ${missing.length > 1 ? "them" : "it"} before 'stop', or declare ${missing.length > 1 ? "them" : "it"} nullable (e.g. '${missing[0].name}: ${parameterTypeName(this.reflection, missing[0])?.name ?? "T"}?').`,
                    { node: stop }
                );
            }
        }
        if (atEnd != undefined) {
            for (const parameter of missingAt(atEnd)) {
                accept(
                    "error",
                    `The output '${parameter.name}' is not assigned on every path on which the transformation succeeds. Assign it with '${parameter.name} = …' in a pattern that always runs, or declare it nullable ('${parameter.name}: ${parameterTypeName(this.reflection, parameter)?.name ?? "T"}?').`,
                    { node: parameter, property: "name" }
                );
            }
        }
    }

    /**
     * Describes a list of outputs for a message.
     */
    private describe(parameters: TransformationParameterType[]): string {
        const names = parameters.map((p) => `'${p.name}'`).join(", ");
        return parameters.length > 1 ? `the outputs ${names}` : `the output ${names}`;
    }

    /**
     * Computes which outputs are definitely assigned when a block of statements completes.
     *
     * @param statements The statements, in order
     * @param assigned The outputs assigned before the block
     * @param stops Collects every `stop` with the outputs assigned when it is reached
     * @returns The outputs assigned after the block, or undefined if it never completes normally
     */
    private analyzeStatements(
        statements: BaseTransformationStatementType[],
        assigned: Set<string>,
        stops: { stop: StopStatementType; assigned: Set<string> }[]
    ): Set<string> | undefined {
        let current: Set<string> | undefined = new Set(assigned);
        for (const statement of statements) {
            if (current == undefined) {
                return undefined;
            }
            current = this.analyzeStatement(statement, current, stops);
        }
        return current;
    }

    /**
     * Computes the outputs definitely assigned after one statement.
     */
    private analyzeStatement(
        statement: BaseTransformationStatementType,
        assigned: Set<string>,
        stops: { stop: StopStatementType; assigned: Set<string> }[]
    ): Set<string> | undefined {
        const block = (scope: StatementsScopeType | undefined, before: Set<string>) =>
            this.analyzeStatements(scope?.statements ?? [], before, stops);

        if (this.reflection.isInstance(statement, MatchStatement)) {
            return this.withPatternAssignments(assigned, statement.pattern);
        }
        if (this.reflection.isInstance(statement, IfMatchStatement)) {
            const ifMatch = statement as IfMatchStatementType;
            const thenResult = block(
                ifMatch.ifBlock?.thenBlock,
                this.withPatternAssignments(assigned, ifMatch.ifBlock?.pattern)
            );
            const elseResult = ifMatch.elseBlock != undefined ? block(ifMatch.elseBlock, assigned) : assigned;
            return this.intersect([thenResult, elseResult]);
        }
        if (
            this.reflection.isInstance(statement, WhileMatchStatement) ||
            this.reflection.isInstance(statement, ForMatchStatement)
        ) {
            const loop = statement as WhileMatchStatementType | ForMatchStatementType;
            block(loop.doBlock, this.withPatternAssignments(assigned, loop.pattern));
            return assigned;
        }
        if (this.reflection.isInstance(statement, UntilMatchStatement)) {
            const loop = statement as UntilMatchStatementType;
            block(loop.doBlock, assigned);
            return this.withPatternAssignments(assigned, loop.pattern);
        }
        if (this.reflection.isInstance(statement, IfExpressionStatement)) {
            const ifExpression = statement as IfExpressionStatementType;
            const results = [
                block(ifExpression.thenBlock, assigned),
                ...(ifExpression.elseIfBranches ?? []).map((branch) => block(branch.block, assigned)),
                ifExpression.elseBlock != undefined ? block(ifExpression.elseBlock, assigned) : assigned
            ];
            return this.intersect(results);
        }
        if (this.reflection.isInstance(statement, WhileExpressionStatement)) {
            block((statement as WhileExpressionStatementType).block, assigned);
            return assigned;
        }
        if (this.reflection.isInstance(statement, StopStatement)) {
            const stop = statement as StopStatementType;
            if (stop.keyword === "stop") {
                stops.push({ stop, assigned });
            }
            return undefined;
        }
        if (this.reflection.isInstance(statement, IfCallStatement)) {
            const ifCall = statement as IfCallStatementType;
            const thenResult = block(ifCall.ifBlock?.thenBlock, assigned);
            const elseResult = ifCall.elseBlock != undefined ? block(ifCall.elseBlock, assigned) : assigned;
            return this.intersect([thenResult, elseResult]);
        }
        if (this.reflection.isInstance(statement, ForInStatement)) {
            block((statement as ForInStatementType).doBlock, assigned);
            return assigned;
        }
        if (this.reflection.isInstance(statement, AssignmentStatement)) {
            const assignment = statement as AssignmentStatementType;
            const target = assignment.variable?.ref;
            if (assignment.operator === "=" && this.reflection.isInstance(target, TransformationParameter)) {
                return new Set([...assigned, target.name]);
            }
            return assigned;
        }
        if (this.reflection.isInstance(statement, CallStatement)) {
            return assigned;
        }
        return assigned;
    }

    /**
     * Adds the outputs a pattern assigns (`out = expression`).
     */
    private withPatternAssignments(assigned: Set<string>, pattern: PatternType | undefined): Set<string> {
        const result = new Set(assigned);
        for (const element of flattenModelScopes(this.reflection, pattern?.elements ?? [])) {
            if (this.reflection.isInstance(element, PatternVariableReassignment)) {
                const target = (element as PatternVariableReassignmentType).variable?.ref;
                if (this.reflection.isInstance(target, TransformationParameter)) {
                    result.add(target.name);
                }
            }
        }
        return result;
    }

    /**
     * Intersects the outputs assigned on several paths that join; paths that do not complete
     * normally (undefined) do not take part.
     */
    private intersect(paths: (Set<string> | undefined)[]): Set<string> | undefined {
        const completing = paths.filter((p): p is Set<string> => p != undefined);
        if (completing.length === 0) {
            return undefined;
        }
        return new Set([...completing[0]].filter((name) => completing.every((p) => p.has(name))));
    }
}
