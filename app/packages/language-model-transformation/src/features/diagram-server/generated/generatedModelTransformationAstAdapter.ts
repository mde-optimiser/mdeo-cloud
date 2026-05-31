import type { TypedExpression } from "@mdeo/language-expression";
import type {
    TypedAst,
    TypedPattern,
    TypedTransformationStatement,
    TypedIfExpressionStatement,
    TypedWhileExpressionStatement,
    TypedIfMatchStatement,
    TypedMatchStatement,
    TypedWhileMatchStatement,
    TypedUntilMatchStatement,
    TypedForMatchStatement,
    TypedStopStatement,
    TypedElseIfBranch,
    TypedPatternObjectInstance
} from "../../../plugin/typedAst.js";
import type { PartialGeneratedModelTransformation } from "../../../grammar/generatedModelTransformationPartialTypes.js";
import type { ModelTransformationType } from "../../../grammar/modelTransformationTypes.js";

type SyntheticNode = {
    $type: string;
    $container?: SyntheticNode;
    $containerProperty?: string;
    $cstNode?: {
        text: string;
    };
    [key: string]: unknown;
};

type SyntheticRef<T> = {
    ref?: T;
    $refText?: string;
};

/**
 * Parses and adapts generated model transformation JSON content into a synthetic AST.
 */
export function adaptGeneratedModelTransformationRoot(
    sourceModel: PartialGeneratedModelTransformation
): ModelTransformationType | undefined {
    return adaptGeneratedModelTransformationText(sourceModel.content);
}

/**
 * Parses and adapts generated model transformation typed-ast JSON text into a synthetic AST.
 */
export function adaptGeneratedModelTransformationText(content: string | undefined): ModelTransformationType | undefined {
    const typedAst = parseTypedAst(content);
    if (typedAst == undefined) {
        return undefined;
    }

    const root: SyntheticNode = {
        $type: "ModelTransformation",
        import: {
            $type: "MetamodelFileImport",
            file: typedAst.metamodelPath
        },
        statements: []
    };

    const statements = typedAst.statements.map((statement) => convertStatement(statement));
    root.statements = statements;
    assignArray(root, "statements", statements);

    return root as unknown as ModelTransformationType;
}

function parseTypedAst(content: string | undefined): TypedAst | undefined {
    if (content == undefined || content.trim().length === 0) {
        return undefined;
    }

    try {
        return JSON.parse(content) as TypedAst;
    } catch {
        return undefined;
    }
}

function convertStatement(statement: TypedTransformationStatement): SyntheticNode {
    switch (statement.kind) {
        case "match":
            return convertMatchStatement(statement as TypedMatchStatement);
        case "ifMatch":
            return convertIfMatchStatement(statement as TypedIfMatchStatement);
        case "whileMatch":
            return convertWhileMatchStatement(statement as TypedWhileMatchStatement);
        case "untilMatch":
            return convertUntilMatchStatement(statement as TypedUntilMatchStatement);
        case "forMatch":
            return convertForMatchStatement(statement as TypedForMatchStatement);
        case "ifExpression":
            return convertIfExpressionStatement(statement as TypedIfExpressionStatement);
        case "whileExpression":
            return convertWhileExpressionStatement(statement as TypedWhileExpressionStatement);
        case "stop":
            return convertStopStatement(statement as TypedStopStatement);
        default:
            return {
                $type: "StopStatement",
                keyword: "stop"
            };
    }
}

function convertMatchStatement(statement: TypedMatchStatement): SyntheticNode {
    const node: SyntheticNode = {
        $type: "MatchStatement",
        pattern: convertPattern(statement.pattern)
    };
    assignChild(node, "pattern", node.pattern as SyntheticNode);
    return node;
}

function convertIfMatchStatement(statement: TypedIfMatchStatement): SyntheticNode {
    const ifBlock: SyntheticNode = {
        $type: "IfMatchConditionAndBlock",
        pattern: convertPattern(statement.pattern),
        thenBlock: createStatementsScope(statement.thenBlock)
    };
    assignChild(ifBlock, "pattern", ifBlock.pattern as SyntheticNode);
    assignChild(ifBlock, "thenBlock", ifBlock.thenBlock as SyntheticNode);

    const node: SyntheticNode = {
        $type: "IfMatchStatement",
        ifBlock,
        elseBlock: statement.elseBlock != undefined ? createStatementsScope(statement.elseBlock) : undefined
    };
    assignChild(node, "ifBlock", ifBlock);
    if (node.elseBlock != undefined) {
        assignChild(node, "elseBlock", node.elseBlock as SyntheticNode);
    }
    return node;
}

function convertWhileMatchStatement(statement: TypedWhileMatchStatement): SyntheticNode {
    const node: SyntheticNode = {
        $type: "WhileMatchStatement",
        pattern: convertPattern(statement.pattern),
        doBlock: createStatementsScope(statement.doBlock)
    };
    assignChild(node, "pattern", node.pattern as SyntheticNode);
    assignChild(node, "doBlock", node.doBlock as SyntheticNode);
    return node;
}

function convertUntilMatchStatement(statement: TypedUntilMatchStatement): SyntheticNode {
    const node: SyntheticNode = {
        $type: "UntilMatchStatement",
        pattern: convertPattern(statement.pattern),
        doBlock: createStatementsScope(statement.doBlock)
    };
    assignChild(node, "pattern", node.pattern as SyntheticNode);
    assignChild(node, "doBlock", node.doBlock as SyntheticNode);
    return node;
}

function convertForMatchStatement(statement: TypedForMatchStatement): SyntheticNode {
    const node: SyntheticNode = {
        $type: "ForMatchStatement",
        pattern: convertPattern(statement.pattern),
        doBlock: createStatementsScope(statement.doBlock)
    };
    assignChild(node, "pattern", node.pattern as SyntheticNode);
    assignChild(node, "doBlock", node.doBlock as SyntheticNode);
    return node;
}

function convertIfExpressionStatement(statement: TypedIfExpressionStatement): SyntheticNode {
    const node: SyntheticNode = {
        $type: "IfExpressionStatement",
        condition: expressionNode(statement.condition),
        thenBlock: createStatementsScope(statement.thenBlock),
        elseIfBranches: statement.elseIfBranches.map((branch) => convertElseIfBranch(branch)),
        elseBlock: statement.elseBlock != undefined ? createStatementsScope(statement.elseBlock) : undefined
    };

    assignChild(node, "condition", node.condition as SyntheticNode);
    assignChild(node, "thenBlock", node.thenBlock as SyntheticNode);
    assignArray(node, "elseIfBranches", node.elseIfBranches as SyntheticNode[]);
    if (node.elseBlock != undefined) {
        assignChild(node, "elseBlock", node.elseBlock as SyntheticNode);
    }

    return node;
}

function convertElseIfBranch(branch: TypedElseIfBranch): SyntheticNode {
    const node: SyntheticNode = {
        $type: "ElseIfBranch",
        condition: expressionNode(branch.condition),
        block: createStatementsScope(branch.block)
    };
    assignChild(node, "condition", node.condition as SyntheticNode);
    assignChild(node, "block", node.block as SyntheticNode);
    return node;
}

function convertWhileExpressionStatement(statement: TypedWhileExpressionStatement): SyntheticNode {
    const node: SyntheticNode = {
        $type: "WhileExpressionStatement",
        condition: expressionNode(statement.condition),
        block: createStatementsScope(statement.block)
    };
    assignChild(node, "condition", node.condition as SyntheticNode);
    assignChild(node, "block", node.block as SyntheticNode);
    return node;
}

function convertStopStatement(statement: TypedStopStatement): SyntheticNode {
    return {
        $type: "StopStatement",
        keyword: statement.keyword
    };
}

function createStatementsScope(statements: TypedTransformationStatement[]): SyntheticNode {
    const scope: SyntheticNode = {
        $type: "StatementsScope",
        statements: statements.map((statement) => convertStatement(statement))
    };
    assignArray(scope, "statements", scope.statements as SyntheticNode[]);
    return scope;
}

function convertPattern(pattern: TypedPattern): SyntheticNode {
    const patternNode: SyntheticNode = {
        $type: "Pattern",
        elements: []
    };

    const localInstances = new Map<string, SyntheticNode>();
    const resolvedLinkInstances = new Map<string, SyntheticNode>();

    const elements: SyntheticNode[] = [];

    for (const element of pattern.elements) {
        const converted = convertPatternElement(element, localInstances, resolvedLinkInstances);
        if (converted != undefined) {
            elements.push(converted);
        }
    }

    patternNode.elements = elements;
    assignArray(patternNode, "elements", elements);
    return patternNode;
}

function convertPatternElement(
    element: TypedPattern["elements"][number],
    localInstances: Map<string, SyntheticNode>,
    resolvedLinkInstances: Map<string, SyntheticNode>
): SyntheticNode | undefined {
    if (element.kind === "variable") {
        return {
            $type: "PatternVariable",
            name: element.variable.name,
            value: expressionNode(element.variable.value)
        };
    }

    if (element.kind === "objectInstance") {
        return convertTypedPatternObjectInstance(element.objectInstance, localInstances, resolvedLinkInstances);
    }

    if (element.kind === "link") {
        return convertPatternLink(element.link, localInstances, resolvedLinkInstances);
    }

    if (element.kind === "whereClause") {
        return {
            $type: "WhereClause",
            expression: expressionNode(element.whereClause.expression)
        };
    }

    return undefined;
}

function convertTypedPatternObjectInstance(
    objectInstance: TypedPatternObjectInstance,
    localInstances: Map<string, SyntheticNode>,
    resolvedLinkInstances: Map<string, SyntheticNode>
): SyntheticNode {
    if (objectInstance.className == undefined || objectInstance.className.length === 0) {
        if (objectInstance.modifier === "delete") {
            const target = resolveInstanceTarget(objectInstance.name, localInstances, resolvedLinkInstances);
            return {
                $type: "PatternObjectDelete",
                instance: {
                    ref: target,
                    $refText: objectInstance.name
                }
            };
        }

        const target = resolveInstanceTarget(objectInstance.name, localInstances, resolvedLinkInstances);
        const node: SyntheticNode = {
            $type: "PatternObjectInstanceReference",
            instance: {
                ref: target,
                $refText: objectInstance.name
            },
            properties: objectInstance.properties.map((property) => convertPatternPropertyAssignment(property))
        };
        assignArray(node, "properties", node.properties as SyntheticNode[]);
        return node;
    }

    const node: SyntheticNode = {
        $type: "PatternObjectInstance",
        modifier:
            objectInstance.modifier != undefined
                ? {
                      $type: "PatternModifier",
                      modifier: objectInstance.modifier
                  }
                : undefined,
        name: objectInstance.name,
        class: {
            $refText: objectInstance.className
        } as SyntheticRef<unknown>,
        properties: objectInstance.properties.map((property) => convertPatternPropertyAssignment(property))
    };

    if (node.modifier != undefined) {
        assignChild(node, "modifier", node.modifier as SyntheticNode);
    }
    assignArray(node, "properties", node.properties as SyntheticNode[]);
    localInstances.set(objectInstance.name, node);
    resolvedLinkInstances.set(objectInstance.name, node);

    return node;
}

function convertPatternPropertyAssignment(property: {
    propertyName: string;
    operator: string;
    value: TypedExpression;
}): SyntheticNode {
    return {
        $type: "PatternPropertyAssignment",
        name: {
            $refText: property.propertyName
        } as SyntheticRef<unknown>,
        operator: property.operator,
        value: expressionNode(property.value)
    };
}

function convertPatternLink(
    link: {
        modifier?: string;
        source: {
            objectName: string;
            propertyName?: string;
        };
        target: {
            objectName: string;
            propertyName?: string;
        };
    },
    localInstances: Map<string, SyntheticNode>,
    resolvedLinkInstances: Map<string, SyntheticNode>
): SyntheticNode {
    const sourceTarget = resolveInstanceTarget(link.source.objectName, localInstances, resolvedLinkInstances);
    const targetTarget = resolveInstanceTarget(link.target.objectName, localInstances, resolvedLinkInstances);

    const sourceEnd: SyntheticNode = {
        $type: "PatternLinkEnd",
        object: {
            ref: sourceTarget,
            $refText: link.source.objectName
        },
        property:
            link.source.propertyName != undefined
                ? ({
                      $refText: link.source.propertyName
                  } as SyntheticRef<unknown>)
                : undefined
    };

    const targetEnd: SyntheticNode = {
        $type: "PatternLinkEnd",
        object: {
            ref: targetTarget,
            $refText: link.target.objectName
        },
        property:
            link.target.propertyName != undefined
                ? ({
                      $refText: link.target.propertyName
                  } as SyntheticRef<unknown>)
                : undefined
    };

    const node: SyntheticNode = {
        $type: "PatternLink",
        modifier:
            link.modifier != undefined
                ? {
                      $type: "PatternModifier",
                      modifier: link.modifier
                  }
                : undefined,
        source: sourceEnd,
        target: targetEnd
    };

    if (node.modifier != undefined) {
        assignChild(node, "modifier", node.modifier as SyntheticNode);
    }
    assignChild(node, "source", sourceEnd);
    assignChild(node, "target", targetEnd);

    return node;
}

function resolveInstanceTarget(
    instanceName: string,
    localInstances: Map<string, SyntheticNode>,
    resolvedLinkInstances: Map<string, SyntheticNode>
): SyntheticNode {
    const localInstance = localInstances.get(instanceName);
    if (localInstance != undefined) {
        return localInstance;
    }

    const cached = resolvedLinkInstances.get(instanceName);
    if (cached != undefined) {
        return cached;
    }

    const placeholder: SyntheticNode = {
        $type: "PatternObjectInstance",
        name: instanceName,
        class: {
            $refText: ""
        } as SyntheticRef<unknown>,
        properties: []
    };
    resolvedLinkInstances.set(instanceName, placeholder);
    return placeholder;
}

function expressionNode(expression: TypedExpression): SyntheticNode {
    return {
        $type: "IdentifierExpression",
        $cstNode: {
            text: renderTypedExpression(expression)
        }
    };
}

function renderTypedExpression(expression: TypedExpression | undefined): string {
    if (expression == undefined) {
        return "?";
    }

    switch (expression.kind) {
        case "identifier":
            return String((expression as { name?: string }).name ?? "?");
        case "stringLiteral":
            return JSON.stringify((expression as { value?: string }).value ?? "");
        case "intLiteral":
        case "longLiteral":
        case "floatLiteral":
        case "doubleLiteral":
            return String((expression as { value?: string }).value ?? "0");
        case "booleanLiteral":
            return String((expression as { value?: boolean }).value ?? false);
        case "nullLiteral":
            return "null";
        case "unary": {
            const unary = expression as unknown as { operator: string; expression: TypedExpression };
            return `${unary.operator}${renderTypedExpression(unary.expression)}`;
        }
        case "binary": {
            const binary = expression as unknown as {
                left: TypedExpression;
                operator: string;
                right: TypedExpression;
            };
            return `${renderTypedExpression(binary.left)} ${binary.operator} ${renderTypedExpression(binary.right)}`;
        }
        case "memberAccess": {
            const member = expression as unknown as {
                expression: TypedExpression;
                member: string;
                isNullChaining?: boolean;
            };
            const accessor = member.isNullChaining ? "?." : ".";
            return `${renderTypedExpression(member.expression)}${accessor}${member.member}`;
        }
        case "call": {
            const call = expression as unknown as { called: TypedExpression; arguments: unknown[] };
            return `${renderTypedExpression(call.called)}(${call.arguments.length})`;
        }
        case "functionCall": {
            const call = expression as unknown as { functionName?: string; arguments: unknown[] };
            return `${call.functionName ?? "fn"}(${call.arguments.length})`;
        }
        case "memberCall": {
            const call = expression as unknown as {
                expression: TypedExpression;
                memberName?: string;
                arguments: unknown[];
                isNullChaining?: boolean;
            };
            const accessor = call.isNullChaining ? "?." : ".";
            return `${renderTypedExpression(call.expression)}${accessor}${call.memberName ?? "call"}(${call.arguments.length})`;
        }
        case "extensionCall": {
            const call = expression as unknown as { extensionName?: string; arguments: unknown[] };
            return `${call.extensionName ?? "extension"}(${call.arguments.length})`;
        }
        case "listLiteral":
            return "[...]";
        case "ternary": {
            const ternary = expression as unknown as {
                condition: TypedExpression;
                trueExpression: TypedExpression;
                falseExpression: TypedExpression;
            };
            return `${renderTypedExpression(ternary.condition)} ? ${renderTypedExpression(ternary.trueExpression)} : ${renderTypedExpression(ternary.falseExpression)}`;
        }
        case "lambda": {
            const lambda = expression as unknown as { parameters?: string[]; body: TypedExpression };
            return `(${(lambda.parameters ?? []).join(", ")}) => ${renderTypedExpression(lambda.body)}`;
        }
        default:
            return expression.kind;
    }
}

function assignChild(parent: SyntheticNode, property: string, child: SyntheticNode): void {
    child.$container = parent;
    child.$containerProperty = property;
}

function assignArray(parent: SyntheticNode, property: string, children: SyntheticNode[]): void {
    for (const child of children) {
        child.$container = parent;
        child.$containerProperty = property;
    }
}
