import type { AstPath, Doc } from "prettier";
import type { CstNode, LangiumCoreServices } from "langium";
import type { AstSerializerAdditionalServices, PrintContext, Print } from "@mdeo/language-common";
import { ID, STRING } from "@mdeo/language-common";
import { sharedImport } from "@mdeo/language-shared";
import { getExpressionPrecedence, Precedence } from "./precedenceHelper.js";
import type {
    ExpressionTypes,
    UnaryExpressionType,
    BinaryExpressionType,
    TernaryExpressionType,
    CallExpressionGenericArgsType,
    CallExpressionType,
    MemberAccessExpressionType,
    MemberCallExpressionType,
    NamedArgumentType,
    IdentifierExpressionType,
    StringLiteralExpressionType,
    IntLiteralExpressionType,
    LongLiteralExpressionType,
    FloatLiteralExpressionType,
    DoubleLiteralExpressionType,
    BooleanLiteralExpressionType,
    BaseExpressionType,
    AssertNonNullExpressionType,
    TypeCastExpressionType,
    TypeCheckExpressionType,
    ListExpressionType
} from "../grammar/expressionTypes.js";

const { doc } = sharedImport("prettier");
const { group, indent, softline, join, line } = doc.builders;
const { isLeafCstNode, isCompositeCstNode } = sharedImport("langium");

/**
 * Registers all expression serializers for pretty-printing expression AST nodes.
 *
 * @param services The Langium core services with AST serializer
 * @param types The generated expression types
 */
export function registerExpressionSerializers(
    services: LangiumCoreServices & AstSerializerAdditionalServices,
    types: ExpressionTypes
): void {
    const { AstSerializer } = services;

    AstSerializer.registerNodeSerializer(types.unaryExpressionType, (ctx) => printUnaryExpression(ctx, types));
    AstSerializer.registerNodeSerializer(types.binaryExpressionType, (ctx) => printBinaryExpression(ctx, types));
    AstSerializer.registerNodeSerializer(types.ternaryExpressionType, (ctx) => printTernaryExpression(ctx, types));
    AstSerializer.registerNodeSerializer(types.callExpressionGenericArgsType, (ctx) =>
        printCallExpressionGenericArgs(ctx)
    );
    AstSerializer.registerNodeSerializer(types.callExpressionType, (ctx) => printCallExpression(ctx, types));
    AstSerializer.registerNodeSerializer(types.namedArgumentType, (ctx) => printNamedArgument(ctx));
    AstSerializer.registerNodeSerializer(types.memberAccessExpressionType, (ctx) =>
        printMemberAccessExpression(ctx, types)
    );
    AstSerializer.registerNodeSerializer(types.memberCallExpressionType, (ctx) =>
        printMemberCallExpression(ctx, types)
    );
    AstSerializer.registerNodeSerializer(types.identifierExpressionType, (ctx) => printIdentifierExpression(ctx));
    AstSerializer.registerNodeSerializer(types.assertNonNullExpressionType, (ctx) =>
        printAssertNonNullExpression(ctx, types)
    );
    AstSerializer.registerNodeSerializer(types.typeCastExpressionType, (ctx) => printTypeCastExpression(ctx, types));
    AstSerializer.registerNodeSerializer(types.typeCheckExpressionType, (ctx) => printTypeCheckExpression(ctx, types));
    AstSerializer.registerNodeSerializer(types.stringLiteralExpressionType, (ctx) => printStringLiteralExpression(ctx));
    AstSerializer.registerNodeSerializer(types.intLiteralExpressionType, (ctx) => printIntLiteralExpression(ctx));
    AstSerializer.registerNodeSerializer(types.longLiteralExpressionType, (ctx) => printLongLiteralExpression(ctx));
    AstSerializer.registerNodeSerializer(types.floatLiteralExpressionType, (ctx) => printFloatLiteralExpression(ctx));
    AstSerializer.registerNodeSerializer(types.doubleLiteralExpressionType, (ctx) => printDoubleLiteralExpression(ctx));
    AstSerializer.registerNodeSerializer(types.booleanLiteralExpressionType, (ctx) =>
        printBooleanLiteralExpression(ctx)
    );
    AstSerializer.registerNodeSerializer(types.nullLiteralExpressionType, () => printNullLiteralExpression());
    AstSerializer.registerNodeSerializer(types.listExpressionType, (ctx) => printListExpression(ctx));
}

/**
 * Helper function to print an expression with precedence awareness.
 * Wraps the expression in parentheses if needed based on precedence comparison.
 *
 * @param path The AST path to print
 * @param parentPrecedence The precedence of the parent expression
 * @param print The print function
 * @param types The generated expression types
 * @returns The formatted expression, potentially wrapped in parentheses
 */
function printWithPrec<T extends BaseExpressionType>(
    path: AstPath<T>,
    parentPrecedence: Precedence,
    print: Print,
    types: ExpressionTypes
): Doc {
    const childPrecedence = getExpressionPrecedence(path.node, types);
    const cstNode = path.node.$cstNode;

    const needsParens =
        childPrecedence > parentPrecedence ||
        (childPrecedence > Precedence.PRIMARY && cstNode != undefined && isBracketedExpression(cstNode));

    const printed = print(path);
    return needsParens ? group(["(", indent([softline, printed]), softline, ")"]) : printed;
}

function isBracketedExpression(node: CstNode): boolean {
    if (!isCompositeCstNode(node)) {
        return false;
    }
    let content = node.content;
    while (true) {
        const visibleContent = content.filter((node) => !node.hidden);
        if (visibleContent.length === 1 && isCompositeCstNode(visibleContent[0])) {
            content = visibleContent[0].content;
        } else if (visibleContent.length === 3) {
            const [first, middle, last] = visibleContent;
            return (
                isLeafCstNode(first) &&
                first.text === "(" &&
                isLeafCstNode(last) &&
                last.text === ")" &&
                isCompositeCstNode(middle)
            );
        } else {
            return false;
        }
    }
}

/**
 * Prints a unary expression node.
 *
 * @param context The print context
 * @param types The generated expression types
 * @returns The formatted unary expression
 */
function printUnaryExpression(context: PrintContext<UnaryExpressionType>, types: ExpressionTypes): Doc {
    const { ctx, printPrimitive, getPrimitive, path, print } = context;
    const operator = printPrimitive(getPrimitive(ctx, "operator"), ID);
    const expression = path.call((p) => printWithPrec(p, Precedence.UNARY, print, types), "expression");
    return [operator, expression];
}

/**
 * Prints a binary expression node.
 *
 * @param context The print context
 * @param types The generated expression types
 * @returns The formatted binary expression
 */
function printBinaryExpression(context: PrintContext<BinaryExpressionType>, types: ExpressionTypes): Doc {
    const { ctx, path, print } = context;

    const precedence = getExpressionPrecedence(ctx, types);

    const left = path.call((p) => printWithPrec(p, precedence, print, types), "left");
    const operator = ctx.operator;
    const right = path.call((p) => printWithPrec(p, precedence - 1, print, types), "right");

    return group([left, " ", operator, " ", right]);
}

/**
 * Prints a ternary expression node.
 *
 * @param context The print context
 * @param types The generated expression types
 * @returns The formatted ternary expression
 */
function printTernaryExpression(context: PrintContext<TernaryExpressionType>, types: ExpressionTypes): Doc {
    const { path, print } = context;

    const condition = path.call((p) => printWithPrec(p, Precedence.TERNARY, print, types), "condition");
    const trueExpression = path.call((p) => printWithPrec(p, Precedence.TERNARY, print, types), "trueExpression");
    const falseExpression = path.call((p) => printWithPrec(p, Precedence.TERNARY, print, types), "falseExpression");

    return group([condition, " ? ", trueExpression, " : ", falseExpression]);
}

/**
 * Prints the argument list of a call.
 *
 * @param argumentDocs The printed arguments, positional ones first and then named ones
 * @returns The formatted argument list, including the parentheses
 */
function printCallArguments(argumentDocs: Doc[]): Doc {
    if (argumentDocs.length === 0) {
        return "()";
    }
    return group(["(", indent([softline, join([",", line], argumentDocs)]), softline, ")"]);
}

/**
 * Prints a named argument node.
 *
 * @param context The print context
 * @returns The formatted named argument
 */
function printNamedArgument(context: PrintContext<NamedArgumentType>): Doc {
    const { printPrimitive, getPrimitive, ctx, path, print } = context;
    return [printPrimitive(getPrimitive(ctx, "name"), ID), " = ", path.call(print, "value")];
}

/**
 * Prints call expression generic arguments node.
 *
 * @param context The print context
 * @returns The formatted generic arguments
 */
function printCallExpressionGenericArgs(context: PrintContext<CallExpressionGenericArgsType>): Doc {
    const { ctx, path, print } = context;
    const docs: Doc[] = [];

    if (ctx.typeArguments && ctx.typeArguments.length > 0) {
        docs.push("<");
        const typeArguments = path.map(print, "typeArguments");
        docs.push(join(", ", typeArguments));
        docs.push(">");
    }

    return docs;
}

/**
 * Prints a call expression node.
 *
 * @param context The print context
 * @param types The generated expression types
 * @returns The formatted call expression
 */
function printCallExpression(context: PrintContext<CallExpressionType>, types: ExpressionTypes): Doc {
    const { ctx, path, print } = context;

    const docs: Doc[] = [path.call((p) => printWithPrec(p, Precedence.POSTFIX, print, types), "expression")];

    if (ctx.genericArgs != undefined) {
        docs.push(path.call(print, "genericArgs"));
    }

    docs.push(printCallArguments([...path.map(print, "arguments"), ...path.map(print, "namedArguments")]));

    return group(docs);
}

/**
 * Prints a member access expression node.
 *
 * @param context The print context
 * @param types The generated expression types
 * @returns The formatted member access expression
 */
function printMemberAccessExpression(context: PrintContext<MemberAccessExpressionType>, types: ExpressionTypes): Doc {
    const { ctx, printPrimitive, getPrimitive, path, print } = context;

    const expression = path.call((p) => printWithPrec(p, Precedence.POSTFIX, print, types), "expression");
    const operator = ctx.isNullChaining ? "?." : ".";
    const member = printPrimitive(getPrimitive(ctx, "member"), ID);
    return [expression, operator, member];
}

/**
 * Prints a member call expression node (e.g. `obj.method(args)`).
 *
 * @param context The print context
 * @param types The generated expression types
 * @returns The formatted member call expression
 */
function printMemberCallExpression(context: PrintContext<MemberCallExpressionType>, types: ExpressionTypes): Doc {
    const { ctx, printPrimitive, getPrimitive, path, print } = context;

    const expression = path.call((p) => printWithPrec(p, Precedence.POSTFIX, print, types), "expression");
    const operator = ctx.isNullChaining ? "?." : ".";
    const member = printPrimitive(getPrimitive(ctx, "member"), ID);

    const docs: Doc[] = [expression, operator, member];

    if (ctx.genericArgs != undefined) {
        docs.push(path.call(print, "genericArgs"));
    }

    docs.push(printCallArguments([...path.map(print, "arguments"), ...path.map(print, "namedArguments")]));

    return group(docs);
}

/**
 * Prints an identifier expression node.
 *
 * @param context The print context
 * @returns The formatted identifier expression
 */
function printIdentifierExpression(context: PrintContext<IdentifierExpressionType>): Doc {
    const { ctx, printPrimitive, getPrimitive } = context;
    return printPrimitive(getPrimitive(ctx, "name"), ID);
}

/**
 * Prints an assert non-null expression node.
 *
 * @param context The print context
 * @param types The generated expression types
 * @returns The formatted assert non-null expression
 */
function printAssertNonNullExpression(context: PrintContext<AssertNonNullExpressionType>, types: ExpressionTypes): Doc {
    const { path, print } = context;
    const expression = path.call((p) => printWithPrec(p, Precedence.UNARY, print, types), "expression");
    return [expression, "!!"];
}

/**
 * Prints a type cast expression node.
 *
 * @param context The print context
 * @param types The generated expression types
 * @returns The formatted type cast expression
 */
function printTypeCastExpression(context: PrintContext<TypeCastExpressionType>, types: ExpressionTypes): Doc {
    const { ctx, path, print } = context;
    const expression = path.call((p) => printWithPrec(p, Precedence.TYPE_CAST, print, types), "expression");
    const typeNode = path.call(print, "targetType");
    return [expression, ctx.isSafe ? " as? " : " as ", typeNode];
}

/**
 * Prints a type check expression node.
 *
 * @param context The print context
 * @param types The generated expression types
 * @returns The formatted type check expression
 */
function printTypeCheckExpression(context: PrintContext<TypeCheckExpressionType>, types: ExpressionTypes): Doc {
    const { ctx, path, print } = context;
    const expression = path.call((p) => printWithPrec(p, Precedence.TYPE_CHECK, print, types), "expression");
    const typeNode = path.call(print, "checkType");
    return [expression, ctx.isNegated ? " !is " : " is ", typeNode];
}

/**
 * Prints a string literal expression node.
 *
 * @param context The print context
 * @returns The formatted string literal expression
 */
function printStringLiteralExpression(context: PrintContext<StringLiteralExpressionType>): Doc {
    const { ctx, printPrimitive, getPrimitive } = context;
    return printPrimitive(getPrimitive(ctx, "value"), STRING);
}

/**
 * Prints an int literal expression node.
 *
 * @param context The print context
 * @returns The formatted int literal expression
 */
function printIntLiteralExpression(context: PrintContext<IntLiteralExpressionType>): Doc {
    const { ctx } = context;
    return ctx.value;
}

/**
 * Prints a long literal expression node.
 *
 * @param context The print context
 * @returns The formatted long literal expression
 */
function printLongLiteralExpression(context: PrintContext<LongLiteralExpressionType>): Doc {
    const { ctx } = context;
    return ctx.value;
}

/**
 * Prints a float literal expression node.
 *
 * @param context The print context
 * @returns The formatted float literal expression
 */
function printFloatLiteralExpression(context: PrintContext<FloatLiteralExpressionType>): Doc {
    const { ctx } = context;
    return ctx.value;
}

/**
 * Prints a double literal expression node.
 *
 * @param context The print context
 * @returns The formatted double literal expression
 */
function printDoubleLiteralExpression(context: PrintContext<DoubleLiteralExpressionType>): Doc {
    const { ctx } = context;
    return ctx.value;
}

/**
 * Prints a boolean literal expression node.
 *
 * @param context The print context
 * @returns The formatted boolean literal expression
 */
function printBooleanLiteralExpression(context: PrintContext<BooleanLiteralExpressionType>): Doc {
    const { ctx } = context;
    return ctx.value ? "true" : "false";
}

/**
 * Prints a null literal expression node.
 *
 * @returns The formatted null literal expression
 */
function printNullLiteralExpression(): Doc {
    return "null";
}

/**
 * Prints a list expression node with square brackets and comma-separated elements.
 *
 * @param context The print context
 * @returns The formatted list expression
 */
function printListExpression(context: PrintContext<ListExpressionType>): Doc {
    const { ctx, path, print } = context;
    if (ctx.elements.length === 0) {
        return "[]";
    }
    return group(["[", indent([softline, join([",", line], path.map(print, "elements"))]), softline, "]"]);
}
