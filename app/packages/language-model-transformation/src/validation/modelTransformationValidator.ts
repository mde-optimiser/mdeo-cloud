import type { ValidationAcceptor, ValidationChecks, AstNode, MultiMap as MultiMapType } from "langium";
import type { ExtendedLangiumServices } from "@mdeo/language-common";
import { Property, resolveClassChain, type ClassType, type PropertyType } from "@mdeo/language-metamodel";
import { BaseModelValidator } from "@mdeo/language-model";
import {
    MatchStatement,
    IfMatchStatement,
    WhileMatchStatement,
    UntilMatchStatement,
    ForMatchStatement,
    IfExpressionStatement,
    WhileExpressionStatement,
    StopStatement,
    Pattern,
    PatternLink,
    PatternObjectInstance,
    PatternVariable,
    type ModelTransformationType,
    type PatternObjectInstanceType,
    type PatternObjectInstanceReferenceType,
    type PatternObjectInstanceDeleteType,
    type PatternLinkType,
    type PatternType,
    type TransformationStatementType,
    type StatementsScopeType,
    type BaseTransformationStatementType,
    type IfExpressionStatementType,
    type WhileExpressionStatementType,
    type ElseIfBranchType,
    type IfMatchStatementType
} from "../grammar/modelTransformationTypes.js";
import { resolveRelativeDocument, sharedImport } from "@mdeo/language-shared";

const { MultiMap, AstUtils } = sharedImport("langium");

/**
 * Issue codes for model transformation validator quick fixes.
 */
export const ModelTransformationIssueCodes = {
    LinkEndpointModifierMismatch: "link-endpoint-modifier-mismatch",
    InstanceModifierLinkMismatch: "instance-modifier-link-mismatch"
} as const;

/**
 * Interface for named elements (objects and variables).
 */
interface NamedElement {
    name: string;
    node: AstNode;
    type: "object" | "variable";
}

/**
 * Interface mapping for model transformation AST types used in validation checks.
 */
interface ModelTransformationAstTypes {
    ModelTransformation: ModelTransformationType;
    PatternObjectInstance: PatternObjectInstanceType;
    PatternObjectInstanceReference: PatternObjectInstanceReferenceType;
    PatternObjectDelete: PatternObjectInstanceDeleteType;
    PatternLink: PatternLinkType;
    StatementsScope: StatementsScopeType;
}

/**
 * Registers validation checks for the model transformation language.
 *
 * @param services The language services
 */
export function registerModelTransformationValidationChecks(services: ExtendedLangiumServices): void {
    const registry = services.validation.ValidationRegistry;
    const validator = new ModelTransformationValidator(services);

    const checks: ValidationChecks<ModelTransformationAstTypes> = {
        ModelTransformation: validator.validateTransformation.bind(validator),
        PatternObjectInstance: validator.validateObjectInstance.bind(validator),
        PatternObjectInstanceReference: validator.validateObjectInstanceReference.bind(validator),
        PatternObjectDelete: validator.validateObjectInstanceDelete.bind(validator),
        PatternLink: validator.validateLink.bind(validator),
        StatementsScope: validator.validateStatementsScopeDeadCode.bind(validator)
    };

    registry.register(checks, validator);
}

/**
 * Validator for model transformation language constructs.
 * Provides validation for global uniqueness, required properties, and link validation.
 */
export class ModelTransformationValidator extends BaseModelValidator {
    /**
     * Creates a new {@link ModelTransformationValidator}.
     *
     * @param services The extended Langium services providing workspace, reflection, and document access
     */
    constructor(private readonly services: ExtendedLangiumServices) {
        super(services.shared.AstReflection);
    }

    /**
     * Validates the entire transformation for global uniqueness of names.
     *
     * @param transformation The model transformation to validate
     * @param accept The validation acceptor
     */
    validateTransformation(transformation: ModelTransformationType, accept: ValidationAcceptor): void {
        this.validateMetamodelImport(transformation, accept);

        const allNames = new MultiMap<string, NamedElement>();
        this.collectAllNames(transformation, allNames);
        this.reportDuplicateNames(allNames, accept);

        this.checkForUnreachableStatements(transformation.statements ?? [], accept);
    }

    /**
     * Validates a statements scope for unreachable statements after a terminating statement.
     *
     * @param scope The statements scope to validate
     * @param accept The validation acceptor
     */
    validateStatementsScopeDeadCode(scope: StatementsScopeType, accept: ValidationAcceptor): void {
        this.checkForUnreachableStatements(scope.statements ?? [], accept);
    }

    /**
     * Checks that the metamodel path in a `using` declaration resolves to an existing document.
     *
     * @param transformation The model transformation whose import declaration is validated
     * @param accept The validation acceptor
     */
    private validateMetamodelImport(transformation: ModelTransformationType, accept: ValidationAcceptor): void {
        const metamodelImport = transformation.import;
        const file = metamodelImport?.file;
        if (file == undefined || file.trim() === "") {
            return;
        }

        const document = AstUtils.getDocument(transformation);
        const targetDoc = resolveRelativeDocument(document, file, this.services.shared.workspace.LangiumDocuments);

        if (targetDoc == undefined) {
            accept("error", `Cannot resolve metamodel path '${file}'. The file does not exist or is not loaded.`, {
                node: metamodelImport,
                property: "file"
            });
        }
    }

    /**
     * Collects all variable and object instance names from the transformation.
     *
     * @param transformation The transformation to collect names from
     * @param names The multimap to add collected names to
     */
    private collectAllNames(transformation: ModelTransformationType, names: MultiMapType<string, NamedElement>): void {
        for (const statement of transformation.statements ?? []) {
            this.collectNamesFromStatement(statement, names);
        }
    }

    /**
     * Collects names from a transformation statement recursively.
     *
     * @param statement The statement to collect names from
     * @param names The multimap to add collected names to
     */
    private collectNamesFromStatement(
        statement: TransformationStatementType,
        names: MultiMapType<string, NamedElement>
    ): void {
        if (this.reflection.isInstance(statement, MatchStatement)) {
            this.collectNamesFromPattern(statement.pattern, names);
        } else if (this.reflection.isInstance(statement, IfMatchStatement)) {
            this.collectNamesFromIfMatchStatement(statement, names);
        } else if (this.reflection.isInstance(statement, WhileMatchStatement)) {
            this.collectNamesFromMatchBlock(statement, names);
        } else if (this.reflection.isInstance(statement, UntilMatchStatement)) {
            this.collectNamesFromMatchBlock(statement, names);
        } else if (this.reflection.isInstance(statement, ForMatchStatement)) {
            this.collectNamesFromMatchBlock(statement, names);
        } else if (this.reflection.isInstance(statement, IfExpressionStatement)) {
            this.collectNamesFromIfExpressionStatement(statement, names);
        } else if (this.reflection.isInstance(statement, WhileExpressionStatement)) {
            this.collectNamesFromWhileExpressionStatement(statement, names);
        }
    }

    /**
     * Collects names from a match-based block statement (while/until/for).
     *
     * @param statement The match block statement
     * @param names The multimap to add collected names to
     */
    private collectNamesFromMatchBlock(
        statement: { pattern: PatternType; doBlock?: { statements?: TransformationStatementType[] } },
        names: MultiMapType<string, NamedElement>
    ): void {
        this.collectNamesFromPattern(statement.pattern, names);
        this.collectNamesFromStatementsScope(statement.doBlock, names);
    }

    /**
     * Collects names from an IfMatchStatement.
     *
     * @param statement The if-match statement
     * @param names The multimap to add collected names to
     */
    private collectNamesFromIfMatchStatement(
        statement: IfMatchStatementType,
        names: MultiMapType<string, NamedElement>
    ): void {
        this.collectNamesFromPattern(statement.ifBlock.pattern, names);
        this.collectNamesFromStatementsScope(statement.ifBlock.thenBlock, names);
        if (statement.elseBlock) {
            this.collectNamesFromStatementsScope(statement.elseBlock, names);
        }
    }

    /**
     * Collects names from an IfExpressionStatement.
     *
     * @param statement The if-expression statement
     * @param names The multimap to add collected names to
     */
    private collectNamesFromIfExpressionStatement(
        statement: IfExpressionStatementType,
        names: MultiMapType<string, NamedElement>
    ): void {
        this.collectNamesFromStatementsScope(statement.thenBlock, names);
        for (const branch of statement.elseIfBranches ?? []) {
            this.collectNamesFromElseIfBranch(branch, names);
        }
        if (statement.elseBlock) {
            this.collectNamesFromStatementsScope(statement.elseBlock, names);
        }
    }

    /**
     * Collects names from an ElseIfBranch.
     *
     * @param branch The else-if branch
     * @param names The multimap to add collected names to
     */
    private collectNamesFromElseIfBranch(branch: ElseIfBranchType, names: MultiMapType<string, NamedElement>): void {
        this.collectNamesFromStatementsScope(branch.block, names);
    }

    /**
     * Collects names from a WhileExpressionStatement.
     *
     * @param statement The while-expression statement
     * @param names The multimap to add collected names to
     */
    private collectNamesFromWhileExpressionStatement(
        statement: WhileExpressionStatementType,
        names: MultiMapType<string, NamedElement>
    ): void {
        this.collectNamesFromStatementsScope(statement.block, names);
    }

    /**
     * Collects names from a statements scope.
     *
     * @param scope The scope containing statements
     * @param names The multimap to add collected names to
     */
    private collectNamesFromStatementsScope(
        scope: { statements?: TransformationStatementType[] } | undefined,
        names: MultiMapType<string, NamedElement>
    ): void {
        for (const statement of scope?.statements ?? []) {
            this.collectNamesFromStatement(statement, names);
        }
    }

    /**
     * Collects names from a pattern.
     *
     * @param pattern The pattern to collect names from
     * @param names The multimap to add collected names to
     */
    private collectNamesFromPattern(pattern: PatternType | undefined, names: MultiMapType<string, NamedElement>): void {
        for (const element of pattern?.elements ?? []) {
            if (this.reflection.isInstance(element, PatternObjectInstance)) {
                if (element.name) {
                    names.add(element.name, { name: element.name, node: element, type: "object" });
                }
            } else if (this.reflection.isInstance(element, PatternVariable)) {
                if (element.name) {
                    names.add(element.name, { name: element.name, node: element, type: "variable" });
                }
            }
        }
    }

    /**
     * Reports duplicate names found in the transformation.
     *
     * @param names The multimap of names to check
     * @param accept The validation acceptor
     */
    private reportDuplicateNames(names: MultiMapType<string, NamedElement>, accept: ValidationAcceptor): void {
        for (const [name, elements] of names.entriesGroupedByKey()) {
            if (elements.length > 1) {
                for (const element of elements) {
                    accept("error", `Duplicate name '${name}': ${element.type} names must be globally unique.`, {
                        node: element.node,
                        property: "name"
                    });
                }
            }
        }
    }

    /**
     * Validates a pattern object instance.
     *
     * @param obj The pattern object instance to validate
     * @param accept The validation acceptor
     */
    validateObjectInstance(obj: PatternObjectInstanceType, accept: ValidationAcceptor): void {
        if (obj.class != undefined) {
            this.validateClassNotAbstract(obj, accept);
            this.validateRequiredPropertiesForCreate(obj, accept);
        } else {
            if (obj.modifier?.modifier === "create") {
                accept("error", `Cannot use 'create' modifier without specifying a class type.`, {
                    node: obj,
                    property: "modifier"
                });
            }
        }

        this.validateConditionalInstanceLinkAttachments(obj, accept);
    }

    /**
     * Validates that the class is not abstract when using create modifier.
     *
     * @param obj The pattern object instance
     * @param accept The validation acceptor
     */
    private validateClassNotAbstract(obj: PatternObjectInstanceType, accept: ValidationAcceptor): void {
        const classType = this.getClassTypeFromObject(obj);
        if (!classType) {
            return;
        }

        const typedClass = classType as { isAbstract?: boolean; name?: string };
        if (typedClass.isAbstract && obj.modifier?.modifier === "create") {
            accept("error", `Cannot create instance of abstract class '${typedClass.name}'.`, {
                node: obj,
                property: "class"
            });
        }
    }

    /**
     * Validates that all required properties are assigned for objects with create modifier.
     *
     * @param obj The pattern object instance to validate
     * @param accept The validation acceptor
     */
    private validateRequiredPropertiesForCreate(obj: PatternObjectInstanceType, accept: ValidationAcceptor): void {
        if (obj.modifier?.modifier !== "create") {
            return;
        }

        const classType = this.getClassTypeFromObject(obj);
        if (!classType) {
            return;
        }

        const assignedNames = this.collectAssignedPropertyNames(obj);
        this.reportMissingRequiredProperties(classType, assignedNames, obj, accept);
    }

    /**
     * Gets the resolved class type from a pattern object instance.
     *
     * @param obj The pattern object instance
     * @returns The resolved class type, or undefined if not resolvable
     */
    private getClassTypeFromObject(obj: PatternObjectInstanceType): ClassType | undefined {
        const classRef = obj.class?.ref;
        if (!classRef) {
            return undefined;
        }
        return this.resolveToClass(classRef);
    }

    /**
     * Collects the names of all assigned properties from an object.
     *
     * @param obj The pattern object instance
     * @returns Set of assigned property names
     */
    private collectAssignedPropertyNames(obj: PatternObjectInstanceType): Set<string> {
        const assignedPropertyNames = new Set<string>();

        for (const propAssign of obj.properties ?? []) {
            const propRef = propAssign.name?.ref;
            if (propRef && this.reflection.isInstance(propRef, Property)) {
                const prop = propRef as PropertyType;
                if (prop.name) {
                    assignedPropertyNames.add(prop.name);
                }
            }
        }

        return assignedPropertyNames;
    }

    /**
     * Reports errors for missing required properties.
     *
     * @param classType The class type to check
     * @param assignedNames Set of already assigned property names
     * @param obj The object to report errors on
     * @param accept The validation acceptor
     */
    private reportMissingRequiredProperties(
        classType: ClassType,
        assignedNames: Set<string>,
        obj: PatternObjectInstanceType,
        accept: ValidationAcceptor
    ): void {
        const classChain = resolveClassChain(classType, this.reflection);

        for (const cls of classChain) {
            const typedClass = cls as { properties?: PropertyType[] };
            for (const prop of typedClass.properties ?? []) {
                if (!prop.name) {
                    continue;
                }
                if (!assignedNames.has(prop.name) && this.isRequiredProperty(prop)) {
                    accept("error", `Required property '${prop.name}' must be assigned for create modifier.`, {
                        node: obj,
                        property: "name"
                    });
                }
            }
        }
    }

    /**
     * Validates a pattern object instance reference.
     *
     * @param ref The pattern object instance reference to validate
     * @param accept The validation acceptor
     */
    validateObjectInstanceReference(ref: PatternObjectInstanceReferenceType, accept: ValidationAcceptor): void {
        const instance = ref.instance?.ref;
        if (!instance) {
            return;
        }

        this.validateReferenceNotInSamePattern(ref, instance, "instance", accept);
        this.validateReferenceTargetModifier(instance, ref, "instance", accept);
    }

    /**
     * Validates a pattern object instance delete.
     *
     * @param del The pattern object delete to validate
     * @param accept The validation acceptor
     */
    validateObjectInstanceDelete(del: PatternObjectInstanceDeleteType, accept: ValidationAcceptor): void {
        const instance = del.instance?.ref;
        if (!instance) {
            return;
        }

        this.validateReferenceNotInSamePattern(del, instance, "instance", accept);
        this.validateReferenceTargetModifier(instance, del, "instance", accept);
    }

    /**
     * Validates that a reference does not refer to an instance defined in the same pattern.
     *
     * @param referenceNode The reference node (PatternObjectInstanceReference or PatternObjectDelete)
     * @param targetInstance The referenced instance
     * @param property The property name for diagnostics
     * @param accept The validation acceptor
     */
    private validateReferenceNotInSamePattern(
        referenceNode: AstNode,
        targetInstance: PatternObjectInstanceType,
        property: string,
        accept: ValidationAcceptor
    ): void {
        const referencePattern = this.findContainingPattern(referenceNode);
        const instancePattern = this.findContainingPattern(targetInstance);

        if (referencePattern != undefined && referencePattern === instancePattern) {
            accept("error", `Cannot reference instance '${targetInstance.name}' defined in the same pattern.`, {
                node: referenceNode,
                property
            });
        }
    }

    /**
     * Validates that a reference target has a compatible modifier (no modifier or create).
     *
     * @param targetInstance The referenced instance
     * @param referenceNode The reference node for diagnostics
     * @param property The property name for diagnostics
     * @param accept The validation acceptor
     */
    private validateReferenceTargetModifier(
        targetInstance: PatternObjectInstanceType,
        referenceNode: AstNode,
        property: string,
        accept: ValidationAcceptor
    ): void {
        const modifier = targetInstance.modifier?.modifier;

        if (modifier != undefined && modifier !== "none" && modifier !== "create") {
            accept(
                "error",
                `Cannot reference a '${modifier}' instance '${targetInstance.name}'. Only instances with no modifier or 'create' can be referenced.`,
                {
                    node: referenceNode,
                    property
                }
            );
        }
    }

    /**
     * Validates a pattern link.
     *
     * @param link The pattern link to validate
     * @param accept The validation acceptor
     */
    validateLink(link: PatternLinkType, accept: ValidationAcceptor): void {
        const source = link.source;
        const target = link.target;

        if (!source?.object?.ref || !target?.object?.ref) {
            return;
        }

        const sourceObj = source.object.ref as PatternObjectInstanceType;
        const targetObj = target.object.ref as PatternObjectInstanceType;

        this.validateLinkBase(link, sourceObj, targetObj, accept);
        this.validateConditionalLinkEndpoints(link, sourceObj, targetObj, accept);
    }

    /**
     * Validates that both endpoints of a pattern link have a modifier compatible with the link's own modifier.
     *
     * Rules:
     * - A link with modifier X may only connect to instances that have no modifier or modifier X.
     * - A link with no modifier may only connect to instances that have no modifier.
     *
     * @param link The pattern link being validated
     * @param sourceObj The resolved source object instance
     * @param targetObj The resolved target object instance
     * @param accept The validation acceptor
     */
    private validateConditionalLinkEndpoints(
        link: PatternLinkType,
        sourceObj: PatternObjectInstanceType,
        targetObj: PatternObjectInstanceType,
        accept: ValidationAcceptor
    ): void {
        this.validateLinkEndpointModifierCompatibility(link, sourceObj, "source", accept);
        this.validateLinkEndpointModifierCompatibility(link, targetObj, "target", accept);
    }

    /**
     * Validates that a single link endpoint instance has a modifier compatible with the link's modifier.
     *
     * An endpoint is compatible when it has no modifier (always accepted as an endpoint by any link)
     * or when its modifier exactly matches the link's modifier.
     *
     * @param link The pattern link being validated
     * @param endpointObject The endpoint object instance to check
     * @param endpointProperty The property name of the endpoint used for diagnostics
     * @param accept The validation acceptor
     */
    private validateLinkEndpointModifierCompatibility(
        link: PatternLinkType,
        endpointObject: PatternObjectInstanceType,
        endpointProperty: "source" | "target",
        accept: ValidationAcceptor
    ): void {
        const linkModifier = link.modifier?.modifier;
        const linkPattern = this.findContainingPattern(link);
        const endpointModifier = this.resolveEffectiveModifier(linkPattern, endpointObject);

        if (endpointModifier == undefined || endpointModifier === "none") {
            return;
        }

        if (endpointModifier === linkModifier) {
            return;
        }

        if (endpointModifier === "delete" && (linkModifier === "require" || linkModifier === "forbid")) {
            return;
        }

        const linkModifierLabel = linkModifier ?? "no modifier";
        accept(
            "error",
            `A '${linkModifierLabel}' link cannot be attached to a '${endpointModifier}' instance '${endpointObject.name}'.`,
            {
                node: link,
                property: endpointProperty,
                data: {
                    code: ModelTransformationIssueCodes.LinkEndpointModifierMismatch,
                    endpointModifier
                }
            }
        );
    }

    /**
     * Resolves the effective modifier of an endpoint object for validation purposes.
     * If the endpoint is from a different pattern than the context and has a 'create' modifier,
     * it is treated as having no modifier (as the creation occurred in a previous match).
     *
     * @param contextPattern The pattern where the validation context is
     * @param endpointObject The endpoint object instance
     * @returns The effective modifier string, or undefined
     */
    private resolveEffectiveModifier(
        contextPattern: PatternType | undefined,
        endpointObject: PatternObjectInstanceType
    ): string | undefined {
        const endpointModifier = endpointObject.modifier?.modifier;

        if (endpointModifier === "create") {
            const endpointPattern = this.findContainingPattern(endpointObject);
            if (contextPattern !== endpointPattern) {
                return undefined;
            }
        }

        return endpointModifier;
    }

    /**
     * Validates that an instance with a non-default modifier is only connected by links
     * that share the same modifier.
     *
     * Rules (from the instance's perspective):
     * - Instances with no modifier are always compatible with any link modifier.
     * - Instances with modifier X may only be connected by links that also have modifier X.
     *
     * @param obj The pattern object instance being validated
     * @param accept The validation acceptor
     */
    private validateConditionalInstanceLinkAttachments(
        obj: PatternObjectInstanceType,
        accept: ValidationAcceptor
    ): void {
        const instanceModifier = obj.modifier?.modifier;

        if (instanceModifier == undefined || instanceModifier === "none") {
            return;
        }

        const pattern = this.findContainingPattern(obj);
        if (pattern == undefined) {
            return;
        }

        const connectedLinks = this.collectConnectedLinks(pattern, obj);
        for (const link of connectedLinks) {
            if (link.modifier?.modifier === instanceModifier) {
                continue;
            }

            if (
                instanceModifier === "delete" &&
                (link.modifier?.modifier === "require" || link.modifier?.modifier === "forbid")
            ) {
                continue;
            }

            accept("error", `A '${instanceModifier}' instance can only be attached to '${instanceModifier}' links.`, {
                node: obj,
                property: "modifier",
                data: {
                    code: ModelTransformationIssueCodes.InstanceModifierLinkMismatch
                }
            });
            return;
        }
    }

    /**
     * Checks a flat list of statements for unreachable code and reports the first
     * statement that follows a definitely-terminating statement.
     *
     * @param statements The ordered list of statements to check
     * @param accept The validation acceptor
     */
    private checkForUnreachableStatements(
        statements: BaseTransformationStatementType[],
        accept: ValidationAcceptor
    ): void {
        for (let i = 0; i < statements.length; i++) {
            if (this.isDefinitelyTerminating(statements[i])) {
                for (let j = i + 1; j < statements.length; j++) {
                    accept("error", "Unreachable statement: this code follows a statement that always terminates.", {
                        node: statements[j]
                    });
                }
                return;
            }
        }
    }

    /**
     * Determines whether a statement will always terminate the surrounding scope,
     * either by being a direct stop/kill statement or by being an if/else construct
     * where every branch is definitely terminating.
     *
     * @param statement The statement to analyse
     * @returns `true` if the statement is guaranteed to terminate the enclosing scope
     */
    private isDefinitelyTerminating(statement: BaseTransformationStatementType): boolean {
        if (this.reflection.isInstance(statement, StopStatement)) {
            return true;
        }
        if (this.reflection.isInstance(statement, IfExpressionStatement)) {
            if (!statement.elseBlock) {
                return false;
            }
            return (
                this.scopeDefinitelyTerminates(statement.thenBlock) &&
                (statement.elseIfBranches ?? []).every((b) => this.scopeDefinitelyTerminates(b.block)) &&
                this.scopeDefinitelyTerminates(statement.elseBlock)
            );
        }
        if (this.reflection.isInstance(statement, IfMatchStatement)) {
            if (!statement.elseBlock) {
                return false;
            }
            return (
                this.scopeDefinitelyTerminates(statement.ifBlock.thenBlock) &&
                this.scopeDefinitelyTerminates(statement.elseBlock)
            );
        }
        return false;
    }

    /**
     * Determines whether a statements scope is definitely terminating, i.e., at least
     * one of its statements is definitely terminating.
     *
     * @param scope The scope to analyse, or `undefined`
     * @returns `true` if the scope definitely terminates; `false` if the scope is absent or has no terminating statement
     */
    private scopeDefinitelyTerminates(scope: StatementsScopeType | undefined): boolean {
        if (scope == undefined) {
            return false;
        }
        return (scope.statements ?? []).some((stmt) => this.isDefinitelyTerminating(stmt));
    }

    /**
     * Finds the nearest parent pattern for a node.
     *
     * @param node The node to inspect
     * @returns The containing pattern, if any
     */
    private findContainingPattern(node: { $container?: AstNode }): PatternType | undefined {
        let current = node.$container;

        while (current != undefined) {
            if (this.reflection.isInstance(current, Pattern)) {
                return current as PatternType;
            }
            current = current.$container;
        }

        return undefined;
    }

    /**
     * Collects links from a pattern that are connected to the given object instance.
     *
     * @param pattern The containing pattern
     * @param objectInstance The object instance whose links should be collected
     * @returns The connected links
     */
    private collectConnectedLinks(pattern: PatternType, objectInstance: PatternObjectInstanceType): PatternLinkType[] {
        const links: PatternLinkType[] = [];

        for (const element of pattern.elements ?? []) {
            if (!this.reflection.isInstance(element, PatternLink)) {
                continue;
            }

            const link = element as PatternLinkType;
            const sourceRef = link.source?.object?.ref;
            const targetRef = link.target?.object?.ref;
            if (sourceRef === objectInstance || targetRef === objectInstance) {
                links.push(link);
            }
        }

        return links;
    }
}
