import type { Doc } from "prettier";
import type { LangiumCoreServices } from "langium";
import type { AstSerializerAdditionalServices, PrintContext } from "@mdeo/language-common";
import { ID } from "@mdeo/language-common";
import { serializeNewlineSep, sharedImport } from "@mdeo/language-shared";
import type {
    StatementTypes,
    StatementsScopeType,
    ElseIfClauseType,
    IfStatementType,
    WhileStatementType,
    ForStatementType,
    ForStatementVariableDeclarationType,
    VariableDeclarationStatementType,
    AssignmentStatementType,
    ExpressionStatementType
} from "../grammar/statementTypes.js";

const { doc } = sharedImport("prettier");
const { indent, hardline } = doc.builders;

/**
 * Registers all statement serializers for pretty-printing statement AST nodes.
 *
 * @param services The Langium core services with AST serializer
 * @param types The generated statement types
 */
export function registerStatementSerializers(
    services: LangiumCoreServices & AstSerializerAdditionalServices,
    types: StatementTypes
): void {
    const { AstSerializer } = services;

    AstSerializer.registerNodeSerializer(types.statementsScopeType, (ctx) => printStatementsScope(ctx));
    AstSerializer.registerNodeSerializer(types.elseIfClauseType, (ctx) => printElseIfClause(ctx));
    AstSerializer.registerNodeSerializer(types.ifStatementType, (ctx) => printIfStatement(ctx));
    AstSerializer.registerNodeSerializer(types.whileStatementType, (ctx) => printWhileStatement(ctx));
    AstSerializer.registerNodeSerializer(types.forStatementType, (ctx) => printForStatement(ctx));
    AstSerializer.registerNodeSerializer(types.forStatementVariableDeclarationType, (ctx) =>
        printForStatementVariableDeclaration(ctx)
    );
    AstSerializer.registerNodeSerializer(types.variableDeclarationStatementType, (ctx) =>
        printVariableDeclarationStatement(ctx)
    );
    AstSerializer.registerNodeSerializer(types.assignmentStatementType, (ctx) => printAssignmentStatement(ctx));
    AstSerializer.registerNodeSerializer(types.expressionStatementType, (ctx) => printExpressionStatement(ctx));
    AstSerializer.registerNodeSerializer(types.breakStatementType, () => printBreakStatement());
    AstSerializer.registerNodeSerializer(types.continueStatementType, () => printContinueStatement());
}

/**
 * Prints a statements scope node.
 *
 * @param context The print context
 * @returns The formatted statements scope
 */
function printStatementsScope(context: PrintContext<StatementsScopeType>): Doc {
    const docs: Doc[] = ["{"];

    const content = serializeNewlineSep(context, ["statements"], doc.builders);
    if (content.length > 0) {
        docs.push(indent([hardline, content]), hardline);
    }

    docs.push("}");
    return docs;
}

/**
 * Prints an else-if clause node.
 *
 * @param context The print context
 * @returns The formatted else-if clause
 */
function printElseIfClause(context: PrintContext<ElseIfClauseType>): Doc {
    const { path, print } = context;
    return ["else if (", path.call(print, "condition"), ") ", path.call(print, "thenBlock")];
}

/**
 * Prints an if statement node.
 *
 * @param context The print context
 * @returns The formatted if statement
 */
function printIfStatement(context: PrintContext<IfStatementType>): Doc {
    const { ctx, path, print } = context;
    const docs: Doc[] = ["if (", path.call(print, "condition"), ") ", path.call(print, "thenBlock")];

    if (ctx.elseIfs && ctx.elseIfs.length > 0) {
        const elseIfs = path.map(print, "elseIfs");
        for (const elseIf of elseIfs) {
            docs.push(" ", elseIf);
        }
    }

    if (ctx.elseBlock != undefined) {
        docs.push(" else ", path.call(print, "elseBlock"));
    }

    return docs;
}

/**
 * Prints a while statement node.
 *
 * @param context The print context
 * @returns The formatted while statement
 */
function printWhileStatement(context: PrintContext<WhileStatementType>): Doc {
    const { path, print } = context;
    return ["while (", path.call(print, "condition"), ") ", path.call(print, "body")];
}

/**
 * Prints a for statement variable declaration node.
 *
 * @param context The print context
 * @returns The formatted for statement variable declaration
 */
function printForStatementVariableDeclaration(context: PrintContext<ForStatementVariableDeclarationType>): Doc {
    const { ctx, printPrimitive, getPrimitive, path, print } = context;
    const docs: Doc[] = [printPrimitive(getPrimitive(ctx, "name"), ID)];

    if (ctx.type != undefined) {
        docs.push(": ", path.call(print, "type"));
    }

    return docs;
}

/**
 * Prints a for statement node.
 *
 * @param context The print context
 * @returns The formatted for statement
 */
function printForStatement(context: PrintContext<ForStatementType>): Doc {
    const { path, print } = context;
    return [
        "for (",
        path.call(print, "variable"),
        " in ",
        path.call(print, "iterable"),
        ") ",
        path.call(print, "body")
    ];
}

/**
 * Prints a variable declaration statement node.
 *
 * @param context The print context
 * @returns The formatted variable declaration statement
 */
function printVariableDeclarationStatement(context: PrintContext<VariableDeclarationStatementType>): Doc {
    const { ctx, printPrimitive, getPrimitive, path, print } = context;
    const docs: Doc[] = [ctx.isReadonly ? "val " : "var ", printPrimitive(getPrimitive(ctx, "name"), ID)];

    if (ctx.type != undefined) {
        docs.push(": ", path.call(print, "type"));
    }

    if (ctx.initialValue != undefined) {
        docs.push(" = ", path.call(print, "initialValue"));
    }

    return docs;
}

/**
 * Prints an assignment statement node.
 *
 * @param context The print context
 * @returns The formatted assignment statement
 */
function printAssignmentStatement(context: PrintContext<AssignmentStatementType>): Doc {
    const { path, print } = context;
    return [path.call(print, "left"), " = ", path.call(print, "right")];
}

/**
 * Prints an expression statement node.
 *
 * @param context The print context
 * @returns The formatted expression statement
 */
function printExpressionStatement(context: PrintContext<ExpressionStatementType>): Doc {
    const { path, print } = context;
    return path.call(print, "expression");
}

/**
 * Prints a break statement node.
 *
 * @param context The print context
 * @returns The formatted break statement
 */
function printBreakStatement(): Doc {
    return "break";
}

/**
 * Prints a continue statement node.
 *
 * @param context The print context
 * @returns The formatted continue statement
 */
function printContinueStatement(): Doc {
    return "continue";
}
