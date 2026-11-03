import type { LangiumCoreServices } from "langium";
import type { AstSerializerAdditionalServices, PrintContext } from "@mdeo/language-common";
import type { Doc } from "prettier";
import { ID, STRING } from "@mdeo/language-common";
import { serializeNewlineSep, sharedImport } from "@mdeo/language-shared";
import {
    PatternModifier,
    StopStatement,
    PatternVariable,
    PatternVariableReassignment,
    PatternPropertyAssignment,
    PatternObjectInstance,
    PatternObjectInstanceDelete,
    PatternLinkEnd,
    PatternLink,
    WhereClause,
    Pattern,
    StatementsScope,
    MatchStatement,
    IfMatchStatement,
    IfMatchConditionAndBlock,
    WhileMatchStatement,
    UntilMatchStatement,
    ForMatchStatement,
    ElseIfBranch,
    IfExpressionStatement,
    WhileExpressionStatement,
    MetamodelFileImport,
    ModelTransformation,
    LambdaParameter,
    LambdaParameters,
    LambdaExpression,
    type PatternModifierType,
    type StopStatementType,
    type PatternVariableType,
    type PatternVariableReassignmentType,
    type PatternPropertyAssignmentType,
    type PatternObjectInstanceType,
    type PatternObjectInstanceDeleteType,
    type PatternLinkEndType,
    type PatternLinkType,
    type WhereClauseType,
    type PatternType,
    type MatchStatementType,
    type IfMatchStatementType,
    type IfMatchConditionAndBlockType,
    type WhileMatchStatementType,
    type UntilMatchStatementType,
    type ForMatchStatementType,
    type ElseIfBranchType,
    type IfExpressionStatementType,
    type WhileExpressionStatementType,
    type MetamodelFileImportType,
    type ModelTransformationType,
    type LambdaParameterType,
    type LambdaParametersType,
    type LambdaExpressionType,
    type StatementsScopeType,
    type PatternObjectInstanceReferenceType,
    PatternObjectInstanceReference,
    PatternApplicationCondition,
    type PatternApplicationConditionType,
    TransformationParameter,
    CallArgument,
    CallOutput,
    CallStatement,
    TransformationFileImport,
    PatternModelScope,
    type TransformationParameterType,
    type TransformationFileImportType,
    type PatternModelScopeType,
    type CallArgumentType,
    type CallOutputType,
    type CallStatementType,
    PatternCollectionAppend,
    IfCallConditionAndBlock,
    IfCallStatement,
    ForInVariable,
    ForInStatement,
    AssignmentStatement,
    type PatternCollectionAppendType,
    type IfCallConditionAndBlockType,
    type IfCallStatementType,
    type ForInVariableType,
    type ForInStatementType,
    type AssignmentStatementType
} from "../grammar/modelTransformationTypes.js";

const { doc } = sharedImport("prettier");
const { indent, hardline, line, group, softline, join } = doc.builders;

/**
 * Language services type combining core and serializer services.
 */
type ModelTransformationLanguageServices = LangiumCoreServices & AstSerializerAdditionalServices;

/**
 * Prints a pattern modifier node.
 *
 * @param context The print context.
 * @returns The formatted modifier keyword.
 */
function printPatternModifier(context: PrintContext<PatternModifierType>): Doc {
    return context.ctx.modifier ?? "";
}

/**
 * Prints a stop statement node.
 *
 * @param context The print context.
 * @returns The formatted stop/kill keyword.
 */
function printStopStatement(context: PrintContext<StopStatementType>): Doc {
    return context.ctx.keyword ?? "stop";
}

/**
 * Registers node serializers for the Model Transformation language.
 * These serializers control how AST nodes are converted back to text.
 *
 * @param services The language services to register serializers with.
 */
export function registerModelTransformationSerializers(services: ModelTransformationLanguageServices): void {
    const { AstSerializer } = services;

    AstSerializer.registerNodeSerializer(PatternModifier, (ctx) => printPatternModifier(ctx));
    AstSerializer.registerNodeSerializer(PatternVariable, (ctx) => printPatternVariable(ctx));
    AstSerializer.registerNodeSerializer(PatternVariableReassignment, (ctx) => printPatternVariableReassignment(ctx));
    AstSerializer.registerNodeSerializer(PatternPropertyAssignment, (ctx) => printPatternPropertyAssignment(ctx));
    AstSerializer.registerNodeSerializer(PatternObjectInstance, (ctx) => printPatternObjectInstance(ctx));
    AstSerializer.registerNodeSerializer(PatternObjectInstanceReference, (ctx) =>
        printPatternObjectInstanceReference(ctx)
    );
    AstSerializer.registerNodeSerializer(PatternObjectInstanceDelete, (ctx) => printPatternObjectInstanceDelete(ctx));
    AstSerializer.registerNodeSerializer(PatternLinkEnd, (ctx) => printPatternLinkEnd(ctx));
    AstSerializer.registerNodeSerializer(PatternLink, (ctx) => printPatternLink(ctx));
    AstSerializer.registerNodeSerializer(WhereClause, (ctx) => printWhereClause(ctx));
    AstSerializer.registerNodeSerializer(PatternApplicationCondition, (ctx) => printPatternApplicationCondition(ctx));
    AstSerializer.registerNodeSerializer(PatternModelScope, (ctx) => printPatternModelScope(ctx));
    AstSerializer.registerNodeSerializer(Pattern, (ctx) => printPattern(ctx));

    AstSerializer.registerNodeSerializer(StatementsScope, (ctx) => printStatementsScope(ctx));
    AstSerializer.registerNodeSerializer(MatchStatement, (ctx) => printMatchStatement(ctx));
    AstSerializer.registerNodeSerializer(IfMatchConditionAndBlock, (ctx) => printIfMatchCondition(ctx));
    AstSerializer.registerNodeSerializer(IfMatchStatement, (ctx) => printIfMatchStatement(ctx));
    AstSerializer.registerNodeSerializer(WhileMatchStatement, (ctx) => printWhileMatchStatement(ctx));
    AstSerializer.registerNodeSerializer(UntilMatchStatement, (ctx) => printUntilMatchStatement(ctx));
    AstSerializer.registerNodeSerializer(ForMatchStatement, (ctx) => printForMatchStatement(ctx));
    AstSerializer.registerNodeSerializer(ElseIfBranch, (ctx) => printElseIfBranch(ctx));
    AstSerializer.registerNodeSerializer(IfExpressionStatement, (ctx) => printIfExpressionStatement(ctx));
    AstSerializer.registerNodeSerializer(WhileExpressionStatement, (ctx) => printWhileExpressionStatement(ctx));
    AstSerializer.registerNodeSerializer(StopStatement, (ctx) => printStopStatement(ctx));

    AstSerializer.registerNodeSerializer(LambdaParameter, (ctx) => printLambdaParameter(ctx));
    AstSerializer.registerNodeSerializer(LambdaParameters, (ctx) => printLambdaParameters(ctx));
    AstSerializer.registerNodeSerializer(LambdaExpression, (ctx) => printLambdaExpression(ctx));

    AstSerializer.registerNodeSerializer(TransformationParameter, (ctx) => printTransformationParameter(ctx));
    AstSerializer.registerNodeSerializer(CallArgument, (ctx) => printCallArgument(ctx));
    AstSerializer.registerNodeSerializer(CallOutput, (ctx) => printCallOutput(ctx));
    AstSerializer.registerNodeSerializer(CallStatement, (ctx) => printCallStatement(ctx));
    AstSerializer.registerNodeSerializer(PatternCollectionAppend, (ctx) => printPatternCollectionAppend(ctx));
    AstSerializer.registerNodeSerializer(IfCallConditionAndBlock, (ctx) => printIfCallCondition(ctx));
    AstSerializer.registerNodeSerializer(IfCallStatement, (ctx) => printIfCallStatement(ctx));
    AstSerializer.registerNodeSerializer(ForInVariable, (ctx) => printForInVariable(ctx));
    AstSerializer.registerNodeSerializer(ForInStatement, (ctx) => printForInStatement(ctx));
    AstSerializer.registerNodeSerializer(AssignmentStatement, (ctx) => printAssignmentStatement(ctx));
    AstSerializer.registerNodeSerializer(TransformationFileImport, (ctx) => printTransformationFileImport(ctx));

    AstSerializer.registerNodeSerializer(MetamodelFileImport, (ctx) => printMetamodelFileImport(ctx));
    AstSerializer.registerNodeSerializer(ModelTransformation, (ctx) => printModelTransformation(ctx));
}

/**
 * Prints a pattern variable node.
 *
 * @param context The print context.
 * @returns The formatted variable declaration.
 */
function printPatternVariable(context: PrintContext<PatternVariableType>): Doc {
    const { ctx, printPrimitive, getPrimitive, path, print } = context;
    const docs: Doc[] = ["var ", printPrimitive(getPrimitive(ctx, "name"), ID)];

    if (ctx.type != undefined) {
        docs.push(": ", path.call(print, "type"));
    }

    docs.push(" = ", path.call(print, "value"));
    return docs;
}

/**
 * Prints a pattern variable reassignment node.
 *
 * @param context The print context.
 * @returns The formatted reassignment (`name = value`).
 */
function printPatternVariableReassignment(context: PrintContext<PatternVariableReassignmentType>): Doc {
    const { path, print, printReference } = context;
    const name = path.call((ref) => printReference(ref, ID), "variable");
    const value = path.call(print, "value");
    return [name, " = ", value];
}

/**
 * Prints an addition to a collection inside a pattern.
 *
 * @param context The print context.
 * @returns The formatted append (`name += value`).
 */
function printPatternCollectionAppend(context: PrintContext<PatternCollectionAppendType>): Doc {
    const { path, print, printReference } = context;
    return [path.call((ref) => printReference(ref, ID), "variable"), " += ", path.call(print, "value")];
}

/**
 * Prints the call and then block of an if-call statement.
 *
 * @param context The print context.
 * @returns The formatted condition (`call f(...) then { ... }`).
 */
function printIfCallCondition(context: PrintContext<IfCallConditionAndBlockType>): Doc {
    const { path, print } = context;
    return [path.call(print, "call"), " then ", path.call(print, "thenBlock")];
}

/**
 * Prints an if-call statement.
 *
 * @param context The print context.
 * @returns The formatted statement.
 */
function printIfCallStatement(context: PrintContext<IfCallStatementType>): Doc {
    const { ctx, path, print } = context;
    const docs: Doc[] = ["if ", path.call(print, "ifBlock")];
    if (ctx.elseBlock != undefined) {
        docs.push(" else ", path.call(print, "elseBlock"));
    }
    return docs;
}

/**
 * Prints the variable of a for-in loop.
 *
 * @param context The print context.
 * @returns The variable's name.
 */
function printForInVariable(context: PrintContext<ForInVariableType>): Doc {
    const { ctx, printPrimitive, getPrimitive } = context;
    return printPrimitive(getPrimitive(ctx, "name"), ID);
}

/**
 * Prints a for-in loop.
 *
 * @param context The print context.
 * @returns The formatted loop (`for x in xs do { ... }`).
 */
function printForInStatement(context: PrintContext<ForInStatementType>): Doc {
    const { path, print } = context;
    return [
        "for ",
        path.call(print, "variable"),
        " in ",
        path.call(print, "collection"),
        " do ",
        path.call(print, "doBlock")
    ];
}

/**
 * Prints an assignment outside a pattern.
 *
 * @param context The print context.
 * @returns The formatted assignment (`name = value` or `name += value`).
 */
function printAssignmentStatement(context: PrintContext<AssignmentStatementType>): Doc {
    const { ctx, path, print, printReference } = context;
    return [
        path.call((ref) => printReference(ref, ID), "variable"),
        ` ${ctx.operator ?? "="} `,
        path.call(print, "value")
    ];
}

/**
 * Prints a pattern property assignment node.
 *
 * @param context The print context.
 * @returns The formatted property assignment.
 */
function printPatternPropertyAssignment(context: PrintContext<PatternPropertyAssignmentType>): Doc {
    const { ctx, path, print, printReference } = context;
    const name = path.call((ref) => printReference(ref, ID), "name");
    const operator = ctx.operator ?? "=";
    const value = path.call(print, "value");
    return [name, " ", operator, " ", value];
}

/**
 * Prints a pattern object instance node.
 *
 * @param context The print context.
 * @returns The formatted object instance.
 */
function printPatternObjectInstance(context: PrintContext<PatternObjectInstanceType>): Doc {
    const { ctx, printPrimitive, getPrimitive, path, print, printReference } = context;
    const docs: Doc[] = [];

    if (ctx.modifier != undefined) {
        docs.push(path.call(print, "modifier"), " ");
    }

    docs.push(printPrimitive(getPrimitive(ctx, "name"), ID));
    docs.push(": ");
    docs.push(path.call((ref) => printReference(ref, ID), "class"));
    docs.push(" {");

    const content = serializeNewlineSep(context, ["properties"], doc.builders);
    if (content.length > 0) {
        docs.push(indent([hardline, content]), hardline);
    }

    docs.push("}");

    return group(docs);
}

/**
 * Prints a pattern object instance reference node.
 * This is used for referencing existing object instances in the pattern, allowing property assignments to modify them.
 *
 * @param context The print context.
 * @returns The formatted object instance reference with property assignments.
 */
function printPatternObjectInstanceReference(context: PrintContext<PatternObjectInstanceReferenceType>): Doc {
    const { path, printReference } = context;
    const docs: Doc[] = [];

    docs.push(path.call((ref) => printReference(ref, ID), "instance"));

    docs.push(" {");

    const content = serializeNewlineSep(context, ["properties"], doc.builders);
    if (content.length > 0) {
        docs.push(indent([hardline, content]), hardline);
    }

    docs.push("}");

    return group(docs);
}

/**
 * Prints a pattern object delete node.
 *
 * @param context The print context.
 * @returns The formatted object delete.
 */
function printPatternObjectInstanceDelete(context: PrintContext<PatternObjectInstanceDeleteType>): Doc {
    const { path, printReference } = context;
    return ["delete ", path.call((ref) => printReference(ref, ID), "instance")];
}

/**
 * Prints a pattern link end node.
 *
 * @param context The print context.
 * @returns The formatted link end.
 */
function printPatternLinkEnd(context: PrintContext<PatternLinkEndType>): Doc {
    const { ctx, path, printReference } = context;
    const docs: Doc[] = [];

    docs.push(path.call((ref) => printReference(ref, ID), "object"));

    if (ctx.property != undefined && ctx.property.$refText) {
        docs.push(".");
        docs.push(ctx.property.$refText);
    }

    return docs;
}

/**
 * Prints a pattern link node.
 *
 * @param context The print context.
 * @returns The formatted link.
 */
function printPatternLink(context: PrintContext<PatternLinkType>): Doc {
    const { ctx, path, print } = context;
    const docs: Doc[] = [];

    if (ctx.modifier != undefined) {
        docs.push(path.call(print, "modifier"), " ");
    }

    const source = path.call(print, "source");
    const target = path.call(print, "target");
    docs.push(source, " -- ", target);

    return docs;
}

/**
 * Prints a where clause node.
 *
 * @param context The print context.
 * @returns The formatted where clause.
 */
function printWhereClause(context: PrintContext<WhereClauseType>): Doc {
    const { path, print } = context;
    return ["where ", path.call(print, "expression")];
}

/**
 * Prints an application condition block (`forbid`/`require`).
 *
 * @param context The print context.
 * @returns The formatted condition block, including its optional name.
 */
function printPatternApplicationCondition(context: PrintContext<PatternApplicationConditionType>): Doc {
    const { ctx, printPrimitive, getPrimitive } = context;
    const docs: Doc[] = [ctx.kind ?? "forbid"];

    if (ctx.name != undefined) {
        docs.push(" ", printPrimitive(getPrimitive(ctx, "name"), ID));
    }

    docs.push(" {");

    const content = serializeNewlineSep(context, ["elements"], doc.builders);
    if (content.length > 0) {
        docs.push(indent([hardline, content]), hardline);
    }

    docs.push("}");

    return group(docs);
}

/**
 * Prints a model scope block (`in model { ... }`).
 *
 * @param context The print context.
 * @returns The formatted scope block.
 */
function printPatternModelScope(context: PrintContext<PatternModelScopeType>): Doc {
    const { path, printReference } = context;
    const docs: Doc[] = ["in ", path.call((ref) => printReference(ref, ID), "model"), " {"];

    const content = serializeNewlineSep(context, ["elements"], doc.builders);
    if (content.length > 0) {
        docs.push(indent([hardline, content]), hardline);
    }

    docs.push("}");

    return group(docs);
}

/**
 * Prints a pattern node.
 *
 * @param context The print context.
 * @returns The formatted pattern.
 */
function printPattern(context: PrintContext<PatternType>): Doc {
    const docs: Doc[] = ["{"];

    const content = serializeNewlineSep(context, ["elements"], doc.builders);
    if (content.length > 0) {
        docs.push(indent([hardline, content]), hardline);
    }

    docs.push("}");

    return group(docs);
}

/**
 * Prints a statements scope node.
 *
 * @param context The print context.
 * @returns The formatted statements scope.
 */
function printStatementsScope(context: PrintContext<StatementsScopeType>): Doc {
    const docs: Doc[] = ["{"];

    const content = serializeNewlineSep(context, ["statements"], doc.builders);
    if (content.length > 0) {
        docs.push(indent([hardline, content]), hardline);
    }

    docs.push("}");

    return group(docs);
}

/**
 * Prints a match statement node.
 *
 * @param context The print context.
 * @returns The formatted match statement.
 */
function printMatchStatement(context: PrintContext<MatchStatementType>): Doc {
    const { path, print } = context;
    return ["match ", path.call(print, "pattern")];
}

/**
 * Prints an if-match condition node (inner node for scoping).
 *
 * @param context The print context.
 * @returns The formatted match condition and then block.
 */
function printIfMatchCondition(context: PrintContext<IfMatchConditionAndBlockType>): Doc {
    const { path, print } = context;
    return ["match ", path.call(print, "pattern"), " then ", path.call(print, "thenBlock")];
}

/**
 * Prints an if-match statement node.
 *
 * @param context The print context.
 * @returns The formatted if-match statement.
 */
function printIfMatchStatement(context: PrintContext<IfMatchStatementType>): Doc {
    const { ctx, path, print } = context;
    const docs: Doc[] = [];

    docs.push("if ", path.call(print, "ifBlock"));

    if (ctx.elseBlock != undefined) {
        docs.push(" else ", path.call(print, "elseBlock"));
    }

    return docs;
}

/**
 * Prints a while-match statement node.
 *
 * @param context The print context.
 * @returns The formatted while-match statement.
 */
function printWhileMatchStatement(context: PrintContext<WhileMatchStatementType>): Doc {
    const { path, print } = context;
    return ["while match ", path.call(print, "pattern"), " do ", path.call(print, "doBlock")];
}

/**
 * Prints an until-match statement node.
 *
 * @param context The print context.
 * @returns The formatted until-match statement.
 */
function printUntilMatchStatement(context: PrintContext<UntilMatchStatementType>): Doc {
    const { path, print } = context;
    return ["until match ", path.call(print, "pattern"), " do ", path.call(print, "doBlock")];
}

/**
 * Prints a for-match statement node.
 *
 * @param context The print context.
 * @returns The formatted for-match statement.
 */
function printForMatchStatement(context: PrintContext<ForMatchStatementType>): Doc {
    const { path, print } = context;
    return ["for match ", path.call(print, "pattern"), " do ", path.call(print, "doBlock")];
}

/**
 * Prints an else-if branch node.
 *
 * @param context The print context.
 * @returns The formatted else-if branch.
 */
function printElseIfBranch(context: PrintContext<ElseIfBranchType>): Doc {
    const { path, print } = context;
    return ["else if (", path.call(print, "condition"), ") ", path.call(print, "block")];
}

/**
 * Prints an if-expression statement node.
 *
 * @param context The print context.
 * @returns The formatted if-expression statement.
 */
function printIfExpressionStatement(context: PrintContext<IfExpressionStatementType>): Doc {
    const { ctx, path, print } = context;
    const docs: Doc[] = [];

    docs.push("if (", path.call(print, "condition"), ") ", path.call(print, "thenBlock"));

    if (ctx.elseIfBranches.length > 0) {
        docs.push(" ", join(" ", path.map(print, "elseIfBranches")));
    }

    if (ctx.elseBlock != undefined) {
        docs.push(" else ", path.call(print, "elseBlock"));
    }

    return docs;
}

/**
 * Prints a while-expression statement node.
 *
 * @param context The print context.
 * @returns The formatted while-expression statement.
 */
function printWhileExpressionStatement(context: PrintContext<WhileExpressionStatementType>): Doc {
    const { path, print } = context;
    return ["while (", path.call(print, "condition"), ") ", path.call(print, "block")];
}

/**
 * Prints a lambda parameter node.
 *
 * @param context The print context.
 * @returns The formatted lambda parameter.
 */
function printLambdaParameter(context: PrintContext<LambdaParameterType>): Doc {
    const { ctx, printPrimitive, getPrimitive } = context;
    return printPrimitive(getPrimitive(ctx, "name"), ID);
}

/**
 * Prints lambda parameters node (with round brackets).
 *
 * @param context The print context.
 * @returns The formatted lambda parameters.
 */
function printLambdaParameters(context: PrintContext<LambdaParametersType>): Doc {
    const { ctx, path, print } = context;
    const docs: Doc[] = [];

    if (ctx.parameters.length > 0) {
        docs.push(group(["(", indent([softline, join([",", line], path.map(print, "parameters"))]), softline, ")"]));
    } else {
        docs.push("()");
    }

    return docs;
}

/**
 * Prints a lambda expression node.
 *
 * @param context The print context.
 * @returns The formatted lambda expression.
 */
function printLambdaExpression(context: PrintContext<LambdaExpressionType>): Doc {
    const { path, print } = context;
    return [path.call(print, "parameterList"), " => ", path.call(print, "expression")];
}

/**
 * Prints a metamodel file import node.
 *
 * @param context The print context.
 * @returns The formatted import.
 */
function printMetamodelFileImport(context: PrintContext<MetamodelFileImportType>): Doc {
    const { ctx, printPrimitive, getPrimitive } = context;
    return ["using ", printPrimitive(getPrimitive(ctx, "file"), STRING)];
}

/**
 * Prints a header declaration.
 *
 * @param context The print context.
 * @returns The formatted parameter, e.g. `in maxCandidates: int = 0`.
 */
function printTransformationParameter(context: PrintContext<TransformationParameterType>): Doc {
    const { ctx, printPrimitive, getPrimitive, path, print } = context;
    const docs: Doc[] = [ctx.direction ?? "in", " ", printPrimitive(getPrimitive(ctx, "name"), ID), ": "];
    docs.push(path.call(print, "type"));
    if (ctx.defaultValue != undefined) {
        docs.push(" = ", path.call(print, "defaultValue"));
    }
    return docs;
}

/**
 * Prints a path import of another transformation.
 *
 * @param context The print context.
 * @returns The formatted import, e.g. `import "./generic/move-component.mt" as moveComponent`.
 */
function printTransformationFileImport(context: PrintContext<TransformationFileImportType>): Doc {
    const { ctx, printPrimitive, getPrimitive } = context;
    return [
        "import ",
        printPrimitive(getPrimitive(ctx, "file"), STRING),
        " as ",
        printPrimitive(getPrimitive(ctx, "name"), ID)
    ];
}

/**
 * Prints a comma-separated list in parentheses that breaks one item per line when it does not
 * fit on the line.
 *
 * @param items The printed items.
 * @returns The formatted list.
 */
function printParenthesizedList(items: Doc[]): Doc {
    if (items.length === 0) {
        return "()";
    }
    return group(["(", indent([softline, join([",", line], items)]), softline, ")"]);
}

/**
 * Prints a call argument.
 *
 * @param context The print context.
 * @returns The formatted argument, e.g. `moved = m`.
 */
function printCallArgument(context: PrintContext<CallArgumentType>): Doc {
    const { path, print, printReference } = context;
    return [path.call((ref) => printReference(ref, ID), "parameter"), " = ", path.call(print, "value")];
}

/**
 * Prints a call output.
 *
 * @param context The print context.
 * @returns The formatted output, e.g. `moved: m`.
 */
function printCallOutput(context: PrintContext<CallOutputType>): Doc {
    const { ctx, path, printReference, printPrimitive, getPrimitive } = context;
    return [
        path.call((ref) => printReference(ref, ID), "parameter"),
        ": ",
        printPrimitive(getPrimitive(ctx, "name"), ID)
    ];
}

/**
 * Prints a call statement.
 *
 * @param context The print context.
 * @returns The formatted call, e.g. `call moveComponent(maxCandidates = 2) -> (moved: m)`.
 */
function printCallStatement(context: PrintContext<CallStatementType>): Doc {
    const { ctx, path, print, printReference } = context;
    const docs: Doc[] = [
        "call ",
        path.call((ref) => printReference(ref, ID), "callee"),
        printParenthesizedList(path.map(print, "arguments"))
    ];
    if ((ctx.outputs ?? []).length > 0) {
        docs.push(" -> ", printParenthesizedList(path.map(print, "outputs")));
    }
    return group(docs);
}

/**
 * Prints the root model transformation node: the `using` line, the imports, the header
 * declarations and the statements, separated by blank lines.
 *
 * @param context The print context.
 * @returns The formatted model transformation.
 */
function printModelTransformation(context: PrintContext<ModelTransformationType>): Doc {
    const { ctx, path, print } = context;
    const docs: Doc[] = [];

    docs.push(path.call(print, "import"));
    docs.push(hardline);

    if ((ctx.imports ?? []).length > 0) {
        docs.push(hardline);
        docs.push(join(hardline, path.map(print, "imports")));
        docs.push(hardline);
    }

    if ((ctx.parameters ?? []).length > 0) {
        docs.push(hardline);
        docs.push(join(hardline, path.map(print, "parameters")));
        docs.push(hardline);
    }

    const content = serializeNewlineSep(context, ["statements"], doc.builders);
    if (content.length > 0) {
        docs.push(hardline);
        docs.push(content);
    }

    return docs;
}
