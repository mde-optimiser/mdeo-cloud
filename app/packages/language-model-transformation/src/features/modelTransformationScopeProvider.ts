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
    type PatternObjectInstanceReferenceType
} from "../grammar/modelTransformationTypes.js";
import { getScopeFromMetamodelFile, resolveClassChain, type ClassType } from "@mdeo/language-metamodel";
import { AssociationEndCache } from "@mdeo/language-model";

const { DefaultScopeProvider, AstUtils, EMPTY_SCOPE } = sharedImport("langium");

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
            this.astReflection.isInstance(referenceInfo.container, PatternVariableReassignment)
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
     * expression and the reassignment target resolve against the same set of variables.
     *
     * @param referenceInfo The reference context.
     * @returns A scope containing all reassignable variables, innermost declarations first.
     */
    private getReassignableVariableScope(referenceInfo: ReferenceInfo): Scope {
        const variables = this.collectVisibleReassignmentVariables(referenceInfo.container);

        const seen = new Set<string>();
        const deduped: PatternVariableType[] = [];
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

        const pattern = reassignment.$container as PatternType | undefined;
        if (pattern == undefined) {
            return result;
        }

        // Variables declared earlier in the same pattern are already available.
        for (const element of pattern.elements ?? []) {
            if (element === reassignment) {
                break;
            }
            if (this.astReflection.isInstance(element, PatternVariable)) {
                result.push(element as PatternVariableType);
            }
        }

        // Walk up the container chain, collecting variables from enclosing scopes.
        let child: AstNode = pattern;
        let container: AstNode | undefined = pattern.$container;
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
     * Appends all {@link PatternVariable} declarations of a pattern to the accumulator.
     *
     * @param pattern The pattern whose variables should be collected (may be undefined).
     * @param result The accumulator for visible variables.
     */
    private addPatternVariables(pattern: PatternType | undefined, result: PatternVariableType[]): void {
        for (const element of pattern?.elements ?? []) {
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
            | PatternObjectInstanceType
            | PatternObjectInstanceReferenceType
            | undefined;

        // workaround for langium weirdness in completion mode
        if (this.astReflection.isInstance(objectInstance, PatternPropertyAssignment)) {
            objectInstance = objectInstance.$container as
                | PatternObjectInstanceType
                | PatternObjectInstanceReferenceType
                | undefined;
        }

        if (objectInstance == undefined) {
            return EMPTY_SCOPE;
        }

        let classRef: ClassType | undefined;
        if (this.astReflection.isInstance(objectInstance, PatternObjectInstance)) {
            classRef = objectInstance.class?.ref as ClassType | undefined;
        } else {
            classRef = objectInstance.instance?.ref?.class?.ref as ClassType | undefined;
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
        const objectRef = linkEnd.object?.ref as PatternObjectInstanceType | undefined;

        if (objectRef == undefined) {
            return EMPTY_SCOPE;
        }

        const classRef = objectRef.class?.ref as ClassType | undefined;
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
     * Returns all pattern object instances in the current pattern.
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
                    scopes.push(
                        localSymbols
                            .getStream(currentNode)
                            .filter((desc) => this.reflection.isSubtype(desc.type, PatternObjectInstance.name))
                    );
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
}
