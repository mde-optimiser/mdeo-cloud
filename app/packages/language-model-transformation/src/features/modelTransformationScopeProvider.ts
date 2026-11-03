import { sharedImport, resolveRelativePath } from "@mdeo/language-shared";
import type { AstReflection, ExtendedLangiumServices } from "@mdeo/language-common";
import type {
    ReferenceInfo,
    Scope,
    AstNodeDescriptionProvider,
    LangiumDocuments,
    AstNode,
    Stream,
    AstNodeDescription
} from "langium";
import {
    getCallee,
    PatternObjectInstance,
    PatternObjectInstanceDelete,
    PatternPropertyAssignment,
    PatternLinkEnd,
    PatternVariable,
    PatternVariableReassignment,
    MatchStatement,
    UntilMatchStatement,
    WhileMatchStatement,
    ForMatchStatement,
    IfMatchConditionAndBlock,
    type PatternObjectInstanceType,
    type PatternPropertyAssignmentType,
    type PatternLinkEndType,
    type PatternVariableType,
    type PatternType,
    ModelTransformation,
    PatternObjectInstanceReference,
    type PatternObjectInstanceReferenceType,
    ObjectInstanceDeclaration,
    PatternCollectionAppend,
    AssignmentStatement,
    Pattern,
    TransformationParameter,
    CallStatement,
    CallArgument,
    CallOutput,
    ForInStatement,
    PatternModelScope,
    type CallStatementType,
    type ForInStatementType,
    type ModelTransformationType,
    type TransformationParameterType
} from "../grammar/modelTransformationTypes.js";
import { getScopeFromMetamodelFile, resolveClassChain, type ClassType } from "@mdeo/language-metamodel";
import { AssociationEndCache } from "@mdeo/language-model";
import {
    TransformationDeclarationResolver,
    flattenModelScopes,
    type TypeInference
} from "./transformationDeclarations.js";

const { DefaultScopeProvider, AstUtils, EMPTY_SCOPE, StreamScope, stream } = sharedImport("langium");

/**
 * Langium scope provider for the Model Transformation language.
 * Handles cross-file reference resolution for class, property, and link end references.
 */
export class ModelTransformationLangiumScopeProvider extends DefaultScopeProvider {
    /**
     * AST reflection service for type checking.
     */
    private readonly astReflection: AstReflection;

    /**
     * Langium documents service for accessing imported files.
     */
    private readonly documents: LangiumDocuments;

    /**
     * Description provider for creating AST node descriptions.
     */
    private readonly descriptionProvider: AstNodeDescriptionProvider;

    /**
     * Cache for association end lookups.
     */
    private readonly associationEndCache: AssociationEndCache;

    /**
     * Resolver for the classes of object declarations (instances, parameters, call outputs).
     */
    private readonly declarations: TransformationDeclarationResolver;

    /**
     * Creates an instance of ModelTransformationLangiumScopeProvider.
     *
     * @param services The language services.
     */
    constructor(services: ExtendedLangiumServices) {
        super(services);
        this.astReflection = services.shared.AstReflection;
        this.documents = services.shared.workspace.LangiumDocuments;
        this.descriptionProvider = services.workspace.AstNodeDescriptionProvider;
        this.associationEndCache = new AssociationEndCache(services);
        this.declarations = new TransformationDeclarationResolver(
            this.astReflection,
            this.documents,
            () => (services as { typir?: { Inference: TypeInference } }).typir?.Inference
        );
    }

    /**
     * Gets the scope for a reference.
     *
     * @param referenceInfo The reference information to resolve.
     * @returns The scope containing potential reference targets.
     */
    override getScope(referenceInfo: ReferenceInfo): Scope {
        const document = AstUtils.getDocument(referenceInfo.container);

        if (
            referenceInfo.property === "model" &&
            this.astReflection.isInstance(referenceInfo.container, PatternModelScope)
        ) {
            return this.getModelValueScope(referenceInfo.container);
        }
        if (
            referenceInfo.property === "callee" &&
            this.astReflection.isInstance(referenceInfo.container, CallStatement)
        ) {
            return this.getCalleeScope(referenceInfo.container);
        }
        if (
            referenceInfo.property === "parameter" &&
            (this.astReflection.isInstance(referenceInfo.container, CallArgument) ||
                this.astReflection.isInstance(referenceInfo.container, CallOutput))
        ) {
            return this.getCalleeParameterScope(referenceInfo.container);
        }
        if (
            referenceInfo.property === "class" &&
            this.astReflection.isInstance(referenceInfo.container, PatternObjectInstance)
        ) {
            return this.getObjectClassScope(referenceInfo, document);
        }
        if (
            referenceInfo.property === "name" &&
            this.astReflection.isInstance(referenceInfo.container, PatternPropertyAssignment)
        ) {
            return this.getPropertyNameScope(referenceInfo);
        }
        if (
            referenceInfo.property === "instance" &&
            this.astReflection.isInstance(referenceInfo.container, PatternObjectInstanceDelete)
        ) {
            return this.getObjectInstancesScope(referenceInfo);
        }
        if (
            referenceInfo.property === "instance" &&
            this.astReflection.isInstance(referenceInfo.container, PatternObjectInstanceReference)
        ) {
            return this.getObjectInstancesScope(referenceInfo);
        }
        if (
            referenceInfo.property === "property" &&
            this.astReflection.isInstance(referenceInfo.container, PatternLinkEnd)
        ) {
            return this.getLinkPropertyScope(referenceInfo);
        }
        if (
            referenceInfo.property === "object" &&
            this.astReflection.isInstance(referenceInfo.container, PatternLinkEnd)
        ) {
            return this.getObjectInstancesScope(referenceInfo);
        }
        if (
            referenceInfo.property === "variable" &&
            (this.astReflection.isInstance(referenceInfo.container, PatternVariableReassignment) ||
                this.astReflection.isInstance(referenceInfo.container, PatternCollectionAppend) ||
                this.astReflection.isInstance(referenceInfo.container, AssignmentStatement))
        ) {
            return this.getReassignableVariableScope(referenceInfo);
        }

        return EMPTY_SCOPE;
    }

    /**
     * Gets the scope for the left-hand side of a pattern variable reassignment
     * (`name = expression`). The target must be a {@link PatternVariable} declared in an
     * enclosing scope, so this returns all variables lexically visible at the reassignment:
     * variables declared earlier in the same pattern, in preceding match/until statements at
     * every enclosing statement block, and in the patterns of enclosing while/until/for-match
     * and if-match statements whose block contains the reassignment.
     *
     * The visibility rules mirror those of the type-system scope provider so that the value
     * expression and the reassignment target resolve against the same set of variables. The
     * header parameters come last: an `out` parameter is assigned this way (the validator
     * rejects assigning an `in` parameter).
     *
     * @param referenceInfo The reference context.
     * @returns A scope containing all reassignable variables, innermost declarations first.
     */
    private getReassignableVariableScope(referenceInfo: ReferenceInfo): Scope {
        const variables: (PatternVariableType | TransformationParameterType)[] =
            this.collectVisibleReassignmentVariables(referenceInfo.container);
        const root = AstUtils.getContainerOfType(referenceInfo.container, (node) =>
            this.astReflection.isInstance(node, ModelTransformation)
        ) as ModelTransformationType | undefined;
        variables.push(...(root?.parameters ?? []));

        const seen = new Set<string>();
        const deduped: (PatternVariableType | TransformationParameterType)[] = [];
        for (const variable of variables) {
            const name = variable.name;
            if (name != undefined && !seen.has(name)) {
                seen.add(name);
                deduped.push(variable);
            }
        }

        return this.createScopeForNodes(deduped);
    }

    /**
     * Collects all pattern variables lexically visible at a reassignment, ordered from the
     * innermost (same-pattern) declarations outward, so callers can resolve name shadowing by
     * keeping the first occurrence of each name.
     *
     * @param reassignment The reassignment node whose target is being resolved.
     * @returns The visible pattern variables, innermost first.
     */
    private collectVisibleReassignmentVariables(reassignment: AstNode): PatternVariableType[] {
        const result: PatternVariableType[] = [];

        const parent = reassignment.$container;
        if (parent == undefined) {
            return result;
        }

        // A statement-level assignment sees the variables of the statements before it; one in a
        // pattern also sees the variables declared earlier in the same pattern.
        let child: AstNode = reassignment;
        if (this.astReflection.isInstance(parent, Pattern)) {
            const index = ((parent as PatternType).elements ?? []).indexOf(reassignment as never);
            const before = ((parent as PatternType).elements ?? []).slice(0, index < 0 ? undefined : index);
            for (const element of flattenModelScopes(this.astReflection, before)) {
                if (this.astReflection.isInstance(element, PatternVariable)) {
                    result.push(element as PatternVariableType);
                }
            }
            child = parent;
        }

        // Walk up the container chain, collecting variables from enclosing scopes.
        let container: AstNode | undefined = child.$container;
        while (container != undefined) {
            this.collectVariablesFromContainer(container, child, result);
            child = container;
            container = container.$container;
        }

        return result;
    }

    /**
     * Adds the variables that a single container contributes to the scope of a descendant
     * reached through [child].
     *
     * - while/until/for-match statements expose their pattern's variables to their `do` block.
     * - if-match conditions expose their pattern's variables to their `then` block.
     * - statement lists (the root transformation and nested blocks) expose the variables of
     *   every preceding match/until-match statement, matching sequential execution order.
     *
     * @param container The container being inspected.
     * @param child The descendant node the walk ascended from.
     * @param result The accumulator for visible variables.
     */
    private collectVariablesFromContainer(container: AstNode, child: AstNode, result: PatternVariableType[]): void {
        if (
            this.astReflection.isInstance(container, WhileMatchStatement) ||
            this.astReflection.isInstance(container, UntilMatchStatement) ||
            this.astReflection.isInstance(container, ForMatchStatement)
        ) {
            const matchLoop = container as unknown as { pattern?: PatternType; doBlock?: AstNode };
            if (child === matchLoop.doBlock) {
                this.addPatternVariables(matchLoop.pattern, result);
            }
            return;
        }

        if (this.astReflection.isInstance(container, IfMatchConditionAndBlock)) {
            const ifMatch = container as unknown as { pattern?: PatternType; thenBlock?: AstNode };
            if (child === ifMatch.thenBlock) {
                this.addPatternVariables(ifMatch.pattern, result);
            }
            return;
        }

        const statements = (container as { statements?: unknown }).statements;
        if (!Array.isArray(statements)) {
            return;
        }

        const childIndex = statements.indexOf(child);
        const upperBound = childIndex < 0 ? statements.length : childIndex;
        for (let i = 0; i < upperBound; i++) {
            const statement = statements[i] as AstNode;
            if (
                this.astReflection.isInstance(statement, MatchStatement) ||
                this.astReflection.isInstance(statement, UntilMatchStatement)
            ) {
                this.addPatternVariables((statement as { pattern?: PatternType }).pattern, result);
            }
        }
    }

    /**
     * Appends all {@link PatternVariable} declarations of a pattern (also those of its model
     * scope blocks) to the accumulator.
     *
     * @param pattern The pattern whose variables should be collected (may be undefined).
     * @param result The accumulator for visible variables.
     */
    private addPatternVariables(pattern: PatternType | undefined, result: PatternVariableType[]): void {
        for (const element of flattenModelScopes(this.astReflection, pattern?.elements ?? [])) {
            if (this.astReflection.isInstance(element, PatternVariable)) {
                result.push(element as PatternVariableType);
            }
        }
    }

    /**
     * Gets the scope for object class references.
     * Resolves the imported metamodel file and returns a scope with all accessible classes.
     *
     * @param referenceInfo The reference context.
     * @param document The current document.
     * @returns A scope containing all accessible classes from the imported metamodel.
     */
    private getObjectClassScope(referenceInfo: ReferenceInfo, document: any): Scope {
        const transformation = AstUtils.getContainerOfType(referenceInfo.container, (node) =>
            this.astReflection.isInstance(node, ModelTransformation)
        );

        const metamodelImport = transformation?.import;
        const relativePath = metamodelImport?.file;

        if (relativePath == undefined) {
            return EMPTY_SCOPE;
        }

        const metamodelUri = resolveRelativePath(document, relativePath);
        const metamodelDoc = this.documents.getDocument(metamodelUri);

        if (metamodelDoc == undefined) {
            return EMPTY_SCOPE;
        }

        return getScopeFromMetamodelFile(metamodelDoc, this.documents, this.descriptionProvider);
    }

    /**
     * Gets the scope for property name references.
     * Resolves properties from the object's class hierarchy.
     *
     * @param referenceInfo The reference context.
     * @returns The scope containing the properties of the object's class chain.
     */
    private getPropertyNameScope(referenceInfo: ReferenceInfo): Scope {
        const propertyAssignment = referenceInfo.container as PatternPropertyAssignmentType;
        let objectInstance = propertyAssignment.$container as
            PatternObjectInstanceType | PatternObjectInstanceReferenceType | undefined;

        // workaround for langium weirdness in completion mode
        if (this.astReflection.isInstance(objectInstance, PatternPropertyAssignment)) {
            objectInstance = objectInstance.$container as
                PatternObjectInstanceType | PatternObjectInstanceReferenceType | undefined;
        }

        if (objectInstance == undefined) {
            return EMPTY_SCOPE;
        }

        let classRef: ClassType | undefined;
        if (this.astReflection.isInstance(objectInstance, PatternObjectInstance)) {
            classRef = objectInstance.class?.ref as ClassType | undefined;
        } else {
            classRef = this.declarations.getDeclaredClass(objectInstance.instance?.ref);
        }
        if (classRef == undefined) {
            return EMPTY_SCOPE;
        }

        const classChain = resolveClassChain(classRef, this.astReflection);
        return this.createScopeForNodes(classChain.flatMap((cls) => cls.properties));
    }

    /**
     * Gets the scope for property references in link ends (association ends).
     * Traverses the class chain and looks up all association ends that reference each class.
     *
     * @param referenceInfo The reference context.
     * @returns The scope containing the association ends of the linked object's class chain.
     */
    private getLinkPropertyScope(referenceInfo: ReferenceInfo): Scope {
        const linkEnd = referenceInfo.container as PatternLinkEndType;
        const classRef = this.declarations.getDeclaredClass(linkEnd.object?.ref);
        if (classRef == undefined) {
            return EMPTY_SCOPE;
        }

        const classChain = resolveClassChain(classRef, this.astReflection);

        const allAssociationEnds = classChain.flatMap((cls) => {
            return this.associationEndCache.getAssociationEndsForClass(cls);
        });

        const uniqueAssociationEnds = Array.from(new Map(allAssociationEnds.map((end) => [end.name, end])).values());

        return this.createScopeForNodes(uniqueAssociationEnds);
    }

    /**
     * Gets the scope for object instance references in pattern link ends.
     * Returns all object declarations visible at the reference: pattern object instances, the
     * header parameters and the outputs of calls.
     *
     * @param referenceInfo The reference context.
     * @returns The scope containing all pattern object instances.
     */
    private getObjectInstancesScope(referenceInfo: ReferenceInfo): Scope {
        const scopes: Stream<AstNodeDescription>[] = [];
        const localSymbols = AstUtils.getDocument(referenceInfo.container).localSymbols;
        if (localSymbols) {
            let currentNode: AstNode | undefined = referenceInfo.container;
            do {
                if (localSymbols.has(currentNode)) {
                    // An optional element input that pins an object of the first pattern has
                    // the object's name; the object comes first, so references resolve to it.
                    const declarations = localSymbols
                        .getStream(currentNode)
                        .filter((desc) => this.reflection.isSubtype(desc.type, ObjectInstanceDeclaration.name))
                        .toArray()
                        .sort(
                            (a, b) =>
                                Number(a.type === TransformationParameter.name) -
                                Number(b.type === TransformationParameter.name)
                        );
                    scopes.push(stream(declarations));
                }
                currentNode = currentNode.$container;
            } while (currentNode != undefined);
        }

        let result: Scope | undefined = undefined;
        for (let i = scopes.length - 1; i >= 0; i--) {
            result = this.createScope(scopes[i], result);
        }
        return result ?? EMPTY_SCOPE;
    }

    /**
     * Gets the scope for the callee of a call: the roots of the transformations imported into
     * this file, each under its alias.
     *
     * @param call The call statement
     * @returns The imported transformations
     */
    private getCalleeScope(call: CallStatementType): Scope {
        const root = AstUtils.getContainerOfType(call, (node) =>
            this.astReflection.isInstance(node, ModelTransformation)
        ) as ModelTransformationType | undefined;
        if (root == undefined) {
            return EMPTY_SCOPE;
        }
        const document = AstUtils.getDocument(root);
        const descriptions: AstNodeDescription[] = [];
        for (const fileImport of root.imports ?? []) {
            if (fileImport.file == undefined || fileImport.name == undefined) {
                continue;
            }
            const targetUri = resolveRelativePath(document, fileImport.file).toString();
            const target = this.indexManager.allElements(ModelTransformation.name, new Set([targetUri])).head();
            if (target != undefined) {
                descriptions.push({ ...target, name: fileImport.name });
            }
        }
        return new StreamScope(stream(descriptions));
    }

    /**
     * Gets the scope for the parameter of a call argument or output: the header parameters of
     * the callee.
     *
     * @param node The call argument or call output
     * @returns The callee's parameters
     */
    private getCalleeParameterScope(node: AstNode): Scope {
        const call = node.$container;
        if (!this.astReflection.isInstance(call, CallStatement)) {
            return EMPTY_SCOPE;
        }
        const callee = getCallee(call as CallStatementType);
        if (callee == undefined) {
            return EMPTY_SCOPE;
        }
        return this.createScopeForNodes(callee.parameters ?? []);
    }

    /**
     * Gets the scope for the model of a scope block (`in name { … }`): the variables of the
     * enclosing `for … in` loops, innermost first, and the header parameters. Which of them is a
     * model value is checked by the validator.
     *
     * @param scopeBlock The scope block
     * @returns The candidate model values
     */
    private getModelValueScope(scopeBlock: AstNode): Scope {
        const candidates: AstNode[] = [];
        for (let node = scopeBlock.$container; node != undefined; node = node.$container) {
            if (this.astReflection.isInstance(node, ForInStatement)) {
                const variable = (node as ForInStatementType).variable;
                if (variable != undefined) {
                    candidates.push(variable);
                }
            }
            if (this.astReflection.isInstance(node, ModelTransformation)) {
                candidates.push(...((node as ModelTransformationType).parameters ?? []));
            }
        }
        return this.createScopeForNodes(candidates);
    }
}
