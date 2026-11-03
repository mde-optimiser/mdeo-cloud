import type { AstReflection } from "@mdeo/language-common";
import { TypedAstConverter, type TypedExpression } from "@mdeo/language-expression";
import { AssociationResolver, type ResolvedAssociation } from "@mdeo/language-model";
import { resolveRelativePath, sharedImport } from "@mdeo/language-shared";
import type { LangiumDocument, LangiumDocuments } from "langium";
import {
    getCallee,
    expressionTypes,
    type ModelTransformationTypirServices,
    type ModelTransformationType,
    type TypedAst,
    type TypedTransformationStatement,
    type MatchStatementType,
    type TypedMatchStatement,
    type IfMatchStatementType,
    type TypedIfMatchStatement,
    type WhileMatchStatementType,
    type TypedWhileMatchStatement,
    type UntilMatchStatementType,
    type TypedUntilMatchStatement,
    type ForMatchStatementType,
    type TypedForMatchStatement,
    type IfExpressionStatementType,
    type TypedIfExpressionStatement,
    type ElseIfBranchType,
    type TypedElseIfBranch,
    type WhileExpressionStatementType,
    type TypedWhileExpressionStatement,
    type StopStatementType,
    type TypedStopStatement,
    type PatternType,
    type TypedPattern,
    type TypedPatternObjectInstanceElement,
    type TypedPatternLinkElement,
    type TypedPatternWhereClauseElement,
    type TypedPatternApplicationCondition,
    type PatternApplicationConditionType,
    type PatternVariableType,
    type TypedPatternVariable,
    type PatternVariableReassignmentType,
    type TypedPatternVariableReassignment,
    type PatternObjectInstanceType,
    type TypedPatternObjectInstance,
    type PatternLinkType,
    type TypedPatternLink,
    type WhereClauseType,
    type TypedWhereClause,
    LambdaExpression,
    type LambdaExpressionType,
    type TypedLambdaExpression,
    IfMatchStatement,
    WhileMatchStatement,
    UntilMatchStatement,
    IfExpressionStatement,
    WhileExpressionStatement,
    StopStatement,
    ForMatchStatement,
    MatchStatement,
    PatternVariable,
    PatternVariableReassignment,
    PatternObjectInstance,
    PatternLink,
    WhereClause,
    PatternObjectInstanceDelete,
    type PatternObjectInstanceDeleteType,
    type PatternObjectInstanceReferenceType,
    PatternObjectInstanceReference,
    PatternApplicationCondition,
    type PatternPropertyOperator,
    CallStatement,
    type CallStatementType,
    type TypedCallStatement,
    type TypedTransformationSignature,
    TransformationDeclarationResolver,
    PatternModelScope,
    type PatternModelScopeType,
    getModelShape,
    IfCallStatement,
    ForInStatement,
    AssignmentStatement,
    PatternCollectionAppend,
    type IfCallStatementType,
    type ForInStatementType,
    type AssignmentStatementType,
    type PatternCollectionAppendType,
    type TypedIfCallStatement,
    type TypedForInStatement,
    type TypedAssignmentStatement,
    type TypedPatternCollectionAppendElement,
    type TransformationParameterType,
    type TypedTransformationParameter
} from "@mdeo/language-model-transformation";
import { AssociationEnd, type AssociationEndType } from "@mdeo/language-metamodel";
import type { AstNode } from "langium";

const { AstUtils, UriUtils } = sharedImport("langium");

/**
 * The element of a typed pattern.
 */
type TypedPatternElementUnion = TypedPattern["elements"][number];

/**
 * The element of a typed application condition.
 */
type TypedConditionElementUnion = TypedPatternApplicationCondition["elements"][number];

/**
 * Model Transformation-specific extension of ExpressionTypedAstConverter.
 * Handles transformation-specific features like patterns, match statements, and lambda expressions.
 * Does not use the base statement types as transformation statements are conceptually different.
 */
export class ModelTransformationTypedAstConverter extends TypedAstConverter {
    /**
     * Expression type identifiers for checking instance types.
     */
    protected expressionTypes = expressionTypes;

    /**
     * Resolver for associations between classes.
     */
    private readonly associationResolver: AssociationResolver;

    /**
     * Resolver for the classes of parameters and call outputs.
     */
    private readonly declarations: TransformationDeclarationResolver;

    /**
     * Creates a new ModelTransformationTypedAstConverter.
     *
     * @param typir The Typir services for type inference
     * @param reflection The AST reflection for type checking
     * @param documents The Langium documents, for the metamodels of the transformations
     */
    constructor(
        protected override readonly typir: ModelTransformationTypirServices,
        reflection: AstReflection,
        documents: LangiumDocuments
    ) {
        super(typir, reflection);
        this.associationResolver = new AssociationResolver(reflection);
        this.declarations = new TransformationDeclarationResolver(reflection, documents, () => typir.Inference);
    }

    /**
     * Converts a ModelTransformation AST node to a TypedAst.
     *
     * @param transformation The ModelTransformation AST node
     * @param document The Langium document containing the transformation (used to resolve absolute paths)
     * @returns The TypedAst representation
     */
    async convertModelTransformation(
        transformation: ModelTransformationType,
        document: LangiumDocument
    ): Promise<TypedAst> {
        const statements: TypedTransformationStatement[] = transformation.statements.map((stmt) =>
            this.convertTransformationStatement(stmt)
        );

        const signature = this.convertSignature(transformation, document);

        return {
            types: this.types,
            metamodelPath: resolveRelativePath(document, transformation.import.file).path,
            statements,
            signature
        };
    }

    /**
     * Converts the header declarations into the signature, named after the file.
     *
     * @param transformation The ModelTransformation AST node
     * @param document The document of the transformation
     * @returns The TypedTransformationSignature representation
     */
    private convertSignature(
        transformation: ModelTransformationType,
        document: LangiumDocument
    ): TypedTransformationSignature {
        return {
            name: UriUtils.basename(document.uri),
            parameters: (transformation.parameters ?? []).map((parameter) => this.convertParameter(parameter))
        };
    }

    /**
     * Converts a header declaration.
     *
     * @param parameter The TransformationParameter AST node
     * @returns The TypedTransformationParameter representation
     */
    private convertParameter(parameter: TransformationParameterType): TypedTransformationParameter {
        const modelShape = getModelShape(this.reflection, parameter);
        if (modelShape != undefined) {
            return {
                name: parameter.name,
                direction: "in",
                type: this.getTypeIndex(parameter.type),
                isElement: false,
                ...(modelShape === "list" ? { collection: "List" as const, elementsAreElements: false } : {}),
                isModel: true
            };
        }
        const shape = this.declarations.getCollectionShape(parameter);
        const isNullable = (parameter.type as { isNullable?: boolean } | undefined)?.isNullable === true;
        const optional = parameter.direction === "in" && parameter.defaultValue == undefined && isNullable;
        const pinsNode = this.declarations.getPinnedObject(parameter) != undefined;
        return {
            name: parameter.name,
            direction: parameter.direction === "out" ? "out" : "in",
            type: this.getTypeIndex(parameter.type),
            isElement: this.declarations.getParameterKind(parameter) === "element",
            ...(parameter.defaultValue != undefined
                ? { defaultValue: this.convertExpression(parameter.defaultValue) }
                : {}),
            ...(shape != undefined
                ? { collection: shape.collection, elementsAreElements: shape.elementKind === "element" }
                : {}),
            ...(optional ? { optional: true } : {}),
            ...(pinsNode ? { pinsNode: true } : {})
        };
    }

    /**
     * Converts an if-call statement.
     *
     * @param stmt The IfCallStatement AST node
     * @returns The TypedIfCallStatement representation
     */
    private convertIfCallStatement(stmt: IfCallStatementType): TypedIfCallStatement {
        return {
            kind: "ifCall",
            call: this.convertCallStatement(stmt.ifBlock.call),
            thenBlock: stmt.ifBlock.thenBlock.statements.map((s) => this.convertTransformationStatement(s)),
            elseBlock: stmt.elseBlock
                ? stmt.elseBlock.statements.map((s) => this.convertTransformationStatement(s))
                : undefined
        };
    }

    /**
     * Converts a for-in loop.
     *
     * @param stmt The ForInStatement AST node
     * @returns The TypedForInStatement representation
     */
    private convertForInStatement(stmt: ForInStatementType): TypedForInStatement {
        return {
            kind: "forIn",
            variable: stmt.variable.name,
            collection: this.convertExpression(stmt.collection),
            doBlock: stmt.doBlock.statements.map((s) => this.convertTransformationStatement(s))
        };
    }

    /**
     * Converts an assignment outside a pattern.
     *
     * @param stmt The AssignmentStatement AST node
     * @returns The TypedAssignmentStatement representation
     */
    private convertAssignmentStatement(stmt: AssignmentStatementType): TypedAssignmentStatement {
        return {
            kind: "assignment",
            name: stmt.variable?.$refText ?? "",
            operator: stmt.operator === "+=" ? "+=" : "=",
            value: this.convertExpression(stmt.value)
        };
    }

    /**
     * Converts an addition to a collection inside a pattern.
     *
     * @param element The PatternCollectionAppend AST node
     * @returns The TypedPatternCollectionAppendElement representation
     */
    private convertPatternCollectionAppend(element: PatternCollectionAppendType): TypedPatternCollectionAppendElement {
        return {
            kind: "collectionAppend",
            append: {
                name: element.variable?.$refText ?? "",
                value: this.convertExpression(element.value)
            }
        };
    }

    /**
     * Converts a call statement. The callee is named by the absolute path of its file, under
     * which the typed AST handler bundles its typed AST, and by the alias it is imported under.
     *
     * @param stmt The CallStatement AST node
     * @returns The TypedCallStatement representation
     */
    private convertCallStatement(stmt: CallStatementType): TypedCallStatement {
        const callee = getCallee(stmt);
        if (callee == undefined) {
            throw new Error(`Call of an unresolved transformation '${stmt.callee?.$refText}'`);
        }
        return {
            kind: "call",
            callee: AstUtils.getDocument(callee).uri.path,
            name: stmt.callee.$refText,
            arguments: (stmt.arguments ?? []).map((argument) => ({
                name: argument.parameter?.$refText ?? "",
                value: this.convertExpression(argument.value)
            })),
            outputs: (stmt.outputs ?? []).map((output) => ({
                parameter: output.parameter?.$refText ?? "",
                name: output.name
            }))
        };
    }

    /**
     * Converts a transformation statement.
     *
     * @param stmt The statement AST node from the parsed grammar
     * @returns The TypedTransformationStatement representation
     * @throws Error if statement type is unknown or not supported
     */
    private convertTransformationStatement(stmt: AstNode): TypedTransformationStatement {
        if (this.reflection.isInstance(stmt, MatchStatement)) {
            return this.convertMatchStatement(stmt);
        }
        if (this.reflection.isInstance(stmt, IfMatchStatement)) {
            return this.convertIfMatchStatement(stmt);
        }
        if (this.reflection.isInstance(stmt, WhileMatchStatement)) {
            return this.convertWhileMatchStatement(stmt);
        }
        if (this.reflection.isInstance(stmt, UntilMatchStatement)) {
            return this.convertUntilMatchStatement(stmt);
        }
        if (this.reflection.isInstance(stmt, ForMatchStatement)) {
            return this.convertForMatchStatement(stmt);
        }
        if (this.reflection.isInstance(stmt, IfExpressionStatement)) {
            return this.convertIfExpressionStatement(stmt);
        }
        if (this.reflection.isInstance(stmt, WhileExpressionStatement)) {
            return this.convertWhileExpressionStatement(stmt);
        }
        if (this.reflection.isInstance(stmt, StopStatement)) {
            return this.convertStopStatement(stmt);
        }
        if (this.reflection.isInstance(stmt, CallStatement)) {
            return this.convertCallStatement(stmt);
        }
        if (this.reflection.isInstance(stmt, IfCallStatement)) {
            return this.convertIfCallStatement(stmt);
        }
        if (this.reflection.isInstance(stmt, ForInStatement)) {
            return this.convertForInStatement(stmt);
        }
        if (this.reflection.isInstance(stmt, AssignmentStatement)) {
            return this.convertAssignmentStatement(stmt);
        }
        throw new Error(`Unknown transformation statement type: ${stmt.$type}`);
    }

    /**
     * Converts a match statement.
     *
     * @param stmt The MatchStatement AST node
     * @returns The TypedMatchStatement representation
     */
    private convertMatchStatement(stmt: MatchStatementType): TypedMatchStatement {
        return {
            kind: "match",
            pattern: this.convertPattern(stmt.pattern)
        };
    }

    /**
     * Converts an if-match statement.
     *
     * @param stmt The IfMatchStatement AST node
     * @returns The TypedIfMatchStatement representation
     */
    private convertIfMatchStatement(stmt: IfMatchStatementType): TypedIfMatchStatement {
        return {
            kind: "ifMatch",
            pattern: this.convertPattern(stmt.ifBlock.pattern),
            thenBlock: stmt.ifBlock.thenBlock.statements.map((s) => this.convertTransformationStatement(s)),
            elseBlock: stmt.elseBlock
                ? stmt.elseBlock.statements.map((s) => this.convertTransformationStatement(s))
                : undefined
        };
    }

    /**
     * Converts a while-match statement.
     *
     * @param stmt The WhileMatchStatement AST node
     * @returns The TypedWhileMatchStatement representation
     */
    private convertWhileMatchStatement(stmt: WhileMatchStatementType): TypedWhileMatchStatement {
        return {
            kind: "whileMatch",
            pattern: this.convertPattern(stmt.pattern),
            doBlock: stmt.doBlock.statements.map((s) => this.convertTransformationStatement(s))
        };
    }

    /**
     * Converts an until-match statement.
     *
     * @param stmt The UntilMatchStatement AST node
     * @returns The TypedUntilMatchStatement representation
     */
    private convertUntilMatchStatement(stmt: UntilMatchStatementType): TypedUntilMatchStatement {
        return {
            kind: "untilMatch",
            pattern: this.convertPattern(stmt.pattern),
            doBlock: stmt.doBlock.statements.map((s) => this.convertTransformationStatement(s))
        };
    }

    /**
     * Converts a for-match statement.
     *
     * @param stmt The ForMatchStatement AST node
     * @returns The TypedForMatchStatement representation
     */
    private convertForMatchStatement(stmt: ForMatchStatementType): TypedForMatchStatement {
        return {
            kind: "forMatch",
            pattern: this.convertPattern(stmt.pattern),
            doBlock: stmt.doBlock.statements.map((s) => this.convertTransformationStatement(s))
        };
    }

    /**
     * Converts an if-expression statement.
     *
     * @param stmt The IfExpressionStatement AST node
     * @returns The TypedIfExpressionStatement representation
     */
    private convertIfExpressionStatement(stmt: IfExpressionStatementType): TypedIfExpressionStatement {
        return {
            kind: "ifExpression",
            condition: this.convertExpression(stmt.condition),
            thenBlock: stmt.thenBlock.statements.map((s) => this.convertTransformationStatement(s)),
            elseIfBranches: stmt.elseIfBranches.map((branch) => this.convertElseIfBranch(branch)),
            elseBlock: stmt.elseBlock
                ? stmt.elseBlock.statements.map((s) => this.convertTransformationStatement(s))
                : undefined
        };
    }

    /**
     * Converts an else-if branch.
     *
     * @param branch The ElseIfBranch AST node
     * @returns The TypedElseIfBranch representation
     */
    private convertElseIfBranch(branch: ElseIfBranchType): TypedElseIfBranch {
        return {
            condition: this.convertExpression(branch.condition),
            block: branch.block.statements.map((s) => this.convertTransformationStatement(s))
        };
    }

    /**
     * Converts a while-expression statement.
     *
     * @param stmt The WhileExpressionStatement AST node
     * @returns The TypedWhileExpressionStatement representation
     */
    private convertWhileExpressionStatement(stmt: WhileExpressionStatementType): TypedWhileExpressionStatement {
        return {
            kind: "whileExpression",
            condition: this.convertExpression(stmt.condition),
            block: stmt.block.statements.map((s) => this.convertTransformationStatement(s))
        };
    }

    /**
     * Converts a stop statement.
     *
     * @param stmt The StopStatement AST node
     * @returns The TypedStopStatement representation
     */
    private convertStopStatement(stmt: StopStatementType): TypedStopStatement {
        return {
            kind: "stop",
            keyword: stmt.keyword
        };
    }

    /**
     * Converts a pattern.
     *
     * Model scope blocks (`in donor { … }`) are flattened: their elements are emitted in their
     * place, and the object instances among them (declared or referenced, also inside nested
     * `forbid` / `require` blocks) carry the name of the block's model value in `model`.
     *
     * @param pattern The Pattern AST node
     * @returns The TypedPattern representation
     */
    private convertPattern(pattern: PatternType): TypedPattern {
        const elements: TypedPatternElementUnion[] = [];
        this.convertPatternElements(pattern.elements, undefined, elements);
        return { elements };
    }

    /**
     * Converts the elements of a pattern or of a model scope block.
     *
     * @param patternElements The elements
     * @param model The model value of the enclosing scope block, if any
     * @param elements Collects the converted elements
     */
    private convertPatternElements(
        patternElements: readonly AstNode[],
        model: string | undefined,
        elements: TypedPatternElementUnion[]
    ): void {
        for (const element of patternElements) {
            if (this.reflection.isInstance(element, PatternModelScope)) {
                const scope = element as PatternModelScopeType;
                this.convertPatternElements(scope.elements ?? [], scope.model?.$refText, elements);
            } else if (this.reflection.isInstance(element, PatternVariable)) {
                elements.push({
                    kind: "variable",
                    variable: this.convertPatternVariable(element)
                });
            } else if (this.reflection.isInstance(element, PatternVariableReassignment)) {
                elements.push({
                    kind: "variableReassignment",
                    reassignment: this.convertPatternVariableReassignment(element)
                });
            } else if (this.reflection.isInstance(element, PatternCollectionAppend)) {
                elements.push(this.convertPatternCollectionAppend(element));
            } else if (this.reflection.isInstance(element, PatternApplicationCondition)) {
                elements.push({
                    kind: "applicationCondition",
                    condition: this.convertApplicationCondition(element, model)
                });
            } else {
                elements.push(this.convertGraphElement(element, model));
            }
        }
    }

    /**
     * Converts an element of a pattern's graph: an object instance, a reference to one, an
     * object deletion, a link or a where clause.
     *
     * @param element The element
     * @param model The model value of the enclosing scope block, if any
     * @returns The converted element
     */
    private convertGraphElement(
        element: AstNode,
        model: string | undefined
    ): TypedPatternObjectInstanceElement | TypedPatternLinkElement | TypedPatternWhereClauseElement {
        const inModel = (objectInstance: TypedPatternObjectInstance): TypedPatternObjectInstanceElement => ({
            kind: "objectInstance",
            objectInstance: model != undefined ? { ...objectInstance, model } : objectInstance
        });
        if (this.reflection.isInstance(element, PatternObjectInstance)) {
            return inModel(this.convertPatternObjectInstance(element));
        }
        if (this.reflection.isInstance(element, PatternObjectInstanceReference)) {
            return inModel(this.convertPatternObjectInstanceReference(element));
        }
        if (this.reflection.isInstance(element, PatternObjectInstanceDelete)) {
            return inModel(this.convertPatternObjectReferenceDelete(element));
        }
        if (this.reflection.isInstance(element, PatternLink)) {
            return { kind: "link", link: this.convertPatternLink(element) };
        }
        if (this.reflection.isInstance(element, WhereClause)) {
            return { kind: "whereClause", whereClause: this.convertWhereClause(element) };
        }
        throw new Error(`Unknown pattern element type: ${element.$type}`);
    }

    /**
     * Converts an application condition block into its own condition graph.
     *
     * Object instances declared inside the block keep their class, while references to
     * instances of the enclosing pattern are emitted without a class name — exactly as for
     * references in a main pattern — so the execution engine can tell condition-local nodes
     * from the anchors that bind the condition to the match. Where clauses are emitted as
     * they are: they belong to the condition, not to the enclosing match. Model scope blocks
     * inside the block are flattened like those of a pattern.
     *
     * @param condition The PatternApplicationCondition AST node
     * @param model The model value of the scope block the condition is in, if any
     * @returns The TypedPatternApplicationCondition representation
     */
    private convertApplicationCondition(
        condition: PatternApplicationConditionType,
        model: string | undefined
    ): TypedPatternApplicationCondition {
        const elements: TypedConditionElementUnion[] = [];
        const convert = (conditionElements: readonly AstNode[], elementModel: string | undefined) => {
            for (const element of conditionElements) {
                if (this.reflection.isInstance(element, PatternModelScope)) {
                    const scope = element as PatternModelScopeType;
                    convert(scope.elements ?? [], scope.model?.$refText);
                } else if (
                    this.reflection.isInstance(element, PatternObjectInstance) ||
                    this.reflection.isInstance(element, PatternObjectInstanceReference) ||
                    this.reflection.isInstance(element, PatternLink) ||
                    this.reflection.isInstance(element, WhereClause)
                ) {
                    elements.push(this.convertGraphElement(element, elementModel));
                } else {
                    throw new Error(`Unsupported application condition element type: ${element.$type}`);
                }
            }
        };
        convert(condition.elements ?? [], model);

        return {
            negative: (condition.kind ?? "forbid") === "forbid",
            name: condition.name,
            elements
        };
    }

    /**
     * Converts a pattern variable.
     *
     * @param variable The PatternVariable AST node
     * @returns The TypedPatternVariable representation
     */
    private convertPatternVariable(variable: PatternVariableType): TypedPatternVariable {
        return {
            name: variable.name,
            type: variable.type ? this.getTypeIndex(variable.type) : undefined,
            value: this.convertExpression(variable.value)
        };
    }

    /**
     * Converts a pattern variable reassignment.
     *
     * @param reassignment The PatternVariableReassignment AST node
     * @returns The TypedPatternVariableReassignment representation
     */
    private convertPatternVariableReassignment(
        reassignment: PatternVariableReassignmentType
    ): TypedPatternVariableReassignment {
        return {
            name: reassignment.variable?.$refText ?? "",
            value: this.convertExpression(reassignment.value)
        };
    }

    /**
     * Converts a pattern object instance.
     *
     * @param obj The PatternObjectInstance AST node
     * @returns The TypedPatternObjectInstance representation
     */
    private convertPatternObjectInstance(obj: PatternObjectInstanceType): TypedPatternObjectInstance {
        return {
            modifier: obj.modifier?.modifier,
            name: obj.name,
            className: obj.class?.$refText ?? "",
            properties: obj.properties.map((prop) => ({
                propertyName: prop.name?.$refText ?? "",
                operator: prop.operator as PatternPropertyOperator,
                value: this.convertExpression(prop.value)
            }))
        };
    }

    /**
     * Converts a pattern object instance reference.
     *
     * @param objRef The PatternObjectInstanceReference AST node
     * @returns The TypedPatternObjectInstance representation without className and with empty properties, as it's just a reference
     */
    private convertPatternObjectInstanceReference(
        objRef: PatternObjectInstanceReferenceType
    ): TypedPatternObjectInstance {
        return {
            name: objRef.instance.$refText,
            properties: objRef.properties.map((prop) => ({
                propertyName: prop.name?.$refText ?? "",
                operator: prop.operator as PatternPropertyOperator,
                value: this.convertExpression(prop.value)
            }))
        };
    }

    /**
     * Converts a pattern object delete element.
     *
     * @param obj The PatternObjectDelete AST node
     * @returns The TypedPatternObjectInstance representation with modifier "delete"
     */
    private convertPatternObjectReferenceDelete(obj: PatternObjectInstanceDeleteType): TypedPatternObjectInstance {
        return {
            modifier: "delete",
            name: obj.instance.$refText,
            className: undefined,
            properties: []
        };
    }

    /**
     * Converts a pattern link.
     *
     * Always outputs the link in the metamodel's canonical direction (metamodel-source
     * as source, metamodel-target as target). When the user writes the link in the
     * opposite direction, source and target are swapped.
     *
     * @param link The PatternLink AST node
     * @returns The TypedPatternLink representation, normalised to metamodel direction
     */
    private convertPatternLink(link: PatternLinkType): TypedPatternLink {
        const sourceObj = link.source.object?.ref;
        const targetObj = link.target.object?.ref;
        const sourceInstanceName = link.source.object?.$refText ?? "";
        const targetInstanceName = link.target.object?.$refText ?? "";

        if (sourceObj == undefined || targetObj == undefined) {
            throw new Error(
                `Pattern link with undefined source or target object: ${sourceInstanceName} -> ${targetInstanceName}`
            );
        }

        const { matchesDirection, association } = this.resolveAssociation(link, sourceObj, targetObj);

        if (!matchesDirection) {
            return {
                modifier: link.modifier?.modifier,
                source: {
                    objectName: targetInstanceName,
                    propertyName: association.source?.name
                },
                target: {
                    objectName: sourceInstanceName,
                    propertyName: association.target?.name
                }
            };
        }

        return {
            modifier: link.modifier?.modifier,
            source: {
                objectName: sourceInstanceName,
                propertyName: association.source?.name
            },
            target: {
                objectName: targetInstanceName,
                propertyName: association.target?.name
            }
        };
    }

    /**
     * Resolves association and direction for a pattern link based on its source and target objects.
     *
     * @param link The PatternLink AST node
     * @param sourceObj The source PatternObjectInstance
     * @param targetObj The target PatternObjectInstance
     * @returns Object containing the resolved association and whether direction matches association direction
     */
    private resolveAssociation(link: PatternLinkType, sourceObj: AstNode, targetObj: AstNode): ResolvedAssociation {
        const sourceClass = this.resolveObjectClass(sourceObj);
        const targetClass = this.resolveObjectClass(targetObj);
        const sourcePropertyRef = link.source.property?.ref;
        const targetPropertyRef = link.target.property?.ref;
        const sourceProperty: AssociationEndType | undefined =
            sourcePropertyRef && this.reflection.isInstance(sourcePropertyRef, AssociationEnd)
                ? sourcePropertyRef
                : undefined;
        const targetProperty: AssociationEndType | undefined =
            targetPropertyRef && this.reflection.isInstance(targetPropertyRef, AssociationEnd)
                ? targetPropertyRef
                : undefined;

        const resolved = this.associationResolver.resolveAssociation(
            sourceClass,
            targetClass,
            sourceProperty,
            targetProperty
        );

        if (!resolved) {
            throw new Error(
                `Unable to resolve association for pattern link between '${sourceClass.name}' and '${targetClass.name}' with properties '${sourceProperty?.name}' and '${targetProperty?.name}'.`
            );
        }

        return resolved;
    }

    /**
     * Resolves the class of an object declaration: a pattern object instance, an element
     * parameter or an element output of a call.
     *
     * @param obj The declaration
     * @returns The ClassType
     */
    private resolveObjectClass(obj: AstNode) {
        const classRef = this.declarations.getDeclaredClass(obj);
        if (classRef == undefined) {
            throw new Error(`Object '${(obj as { name?: string }).name}' has no class`);
        }
        return classRef;
    }

    /**
     * Converts a where clause.
     *
     * @param clause The WhereClause AST node
     * @returns The TypedWhereClause representation
     */
    private convertWhereClause(clause: WhereClauseType): TypedWhereClause {
        return {
            expression: this.convertExpression(clause.expression)
        };
    }

    /**
     * Handles expression conversion, including lambda expressions.
     *
     * @param expr The expression AST node
     * @returns The TypedExpression representation
     * @throws Error if expression type is unsupported
     */
    protected override convertAdditionalExpression(expr: AstNode): TypedExpression {
        if (this.reflection.isInstance(expr, LambdaExpression)) {
            return this.convertLambdaExpression(expr);
        }
        return super.convertAdditionalExpression(expr);
    }

    /**
     * Converts a lambda expression.
     *
     * @param expr The lambda expression AST node
     * @returns The TypedLambdaExpression representation
     */
    private convertLambdaExpression(expr: LambdaExpressionType): TypedLambdaExpression {
        const parameters = expr.parameterList.parameters.map((param) => param.name);

        return {
            kind: "lambda",
            evalType: this.getTypeIndex(expr),
            parameters,
            body: this.convertExpression(expr.expression)
        };
    }
}
