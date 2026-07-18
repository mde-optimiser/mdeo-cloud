import type { AstReflection } from "@mdeo/language-common";
import { TypedAstConverter, type TypedExpression } from "@mdeo/language-expression";
import { AssociationResolver, type ResolvedAssociation } from "@mdeo/language-model";
import { resolveRelativePath } from "@mdeo/language-shared";
import type { LangiumDocument } from "langium";
import {
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
    type TypedPatternVariableElement,
    type TypedPatternVariableReassignmentElement,
    type TypedPatternObjectInstanceElement,
    type TypedPatternLinkElement,
    type TypedPatternWhereClauseElement,
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
    type PatternPropertyOperator
} from "@mdeo/language-model-transformation";
import { AssociationEnd, type AssociationEndType } from "@mdeo/language-metamodel";
import type { AstNode } from "langium";

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
     * Creates a new ModelTransformationTypedAstConverter.
     *
     * @param typir The Typir services for type inference
     * @param reflection The AST reflection for type checking
     */
    constructor(
        protected override readonly typir: ModelTransformationTypirServices,
        reflection: AstReflection
    ) {
        super(typir, reflection);
        this.associationResolver = new AssociationResolver(reflection);
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

        return {
            types: this.types,
            metamodelPath: resolveRelativePath(document, transformation.import.file).path,
            statements
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
     * @param pattern The Pattern AST node
     * @returns The TypedPattern representation
     */
    private convertPattern(pattern: PatternType): TypedPattern {
        const elements: (
            | TypedPatternVariableElement
            | TypedPatternVariableReassignmentElement
            | TypedPatternObjectInstanceElement
            | TypedPatternLinkElement
            | TypedPatternWhereClauseElement
        )[] = [];

        for (const element of pattern.elements) {
            if (this.reflection.isInstance(element, PatternVariable)) {
                elements.push({
                    kind: "variable",
                    variable: this.convertPatternVariable(element)
                });
            } else if (this.reflection.isInstance(element, PatternVariableReassignment)) {
                elements.push({
                    kind: "variableReassignment",
                    reassignment: this.convertPatternVariableReassignment(element)
                });
            } else if (this.reflection.isInstance(element, PatternObjectInstance)) {
                elements.push({
                    kind: "objectInstance",
                    objectInstance: this.convertPatternObjectInstance(element)
                });
            } else if (this.reflection.isInstance(element, PatternLink)) {
                elements.push({
                    kind: "link",
                    link: this.convertPatternLink(element)
                });
            } else if (this.reflection.isInstance(element, WhereClause)) {
                elements.push({
                    kind: "whereClause",
                    whereClause: this.convertWhereClause(element)
                });
            } else if (this.reflection.isInstance(element, PatternObjectInstanceDelete)) {
                elements.push({
                    kind: "objectInstance",
                    objectInstance: this.convertPatternObjectReferenceDelete(element)
                });
            } else if (this.reflection.isInstance(element, PatternObjectInstanceReference)) {
                elements.push({
                    kind: "objectInstance",
                    objectInstance: this.convertPatternObjectInstanceReference(element)
                });
            } else {
                throw new Error(`Unknown pattern element type: ${element.$type}`);
            }
        }

        return { elements };
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
    private resolveAssociation(
        link: PatternLinkType,
        sourceObj: PatternObjectInstanceType,
        targetObj: PatternObjectInstanceType
    ): ResolvedAssociation {
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
     * Resolves the class type for a pattern object instance.
     *
     * @param obj The PatternObjectInstance AST node
     * @returns The ClassType
     */
    private resolveObjectClass(obj: PatternObjectInstanceType) {
        const classRef = obj.class?.ref;
        if (classRef == undefined) {
            throw new Error(`PatternObjectInstance ${obj.name} has no class reference`);
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
