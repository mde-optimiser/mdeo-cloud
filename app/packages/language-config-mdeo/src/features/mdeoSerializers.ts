import type { Doc } from "prettier";
import type { AstNode, LangiumCoreServices } from "langium";
import type { AstSerializerAdditionalServices, PrintContext } from "@mdeo/language-common";
import { ID, STRING, INT, FLOAT } from "@mdeo/language-common";
import { sharedImport, serializeNewlineSep } from "@mdeo/language-shared";
import {
    SearchSection,
    SolverSection,
    MutationsBlock,
    CrossoversBlock,
    UsingPath,
    OperatorArguments,
    OperatorArgument,
    IntLiteral,
    LongLiteral,
    FloatLiteral,
    DoubleLiteral,
    StringLiteral,
    BooleanLiteral,
    NullLiteral,
    EnumLiteral,
    ListLiteral,
    CrossoverBlock,
    type CrossoversBlockType,
    type CrossoverBlockType,
    type OperatorArgumentsType,
    type OperatorArgumentType,
    type IntLiteralType,
    type StringLiteralType,
    type EnumLiteralType,
    type ListLiteralType,
    ClassMutation,
    EdgeMutation,
    MutationStepNumeric,
    MutationStepFixed,
    MutationStepFixedN,
    MutationStepInterval,
    MutationBlock,
    ArchiveBlock,
    AlgorithmParameters,
    TerminationBlock,
    RuntimeSection,
    RuntimeTimeoutBlock,
    RuntimeResourcesBlock,
    type SearchSectionType,
    type SolverSectionType,
    type MutationsBlockType,
    type UsingPathType,
    type ClassMutationType,
    type EdgeMutationType,
    type MutationStepNumericType,
    type MutationStepFixedNType,
    type MutationStepIntervalType,
    type MutationBlockType,
    type ArchiveBlockType,
    type AlgorithmParametersType,
    type TerminationBlockType,
    type RuntimeSectionType,
    type RuntimeTimeoutBlockType,
    type RuntimeResourcesBlockType
} from "../grammar/mdeoTypes.js";

const { doc } = sharedImport("prettier");
const { indent, hardline, group, softline, line, join } = doc.builders;

/**
 * Registers all MDEO serializers for pretty-printing AST nodes.
 *
 * @param services The Langium core services with AST serializer
 */
export function registerMdeoSerializers(services: LangiumCoreServices & AstSerializerAdditionalServices): void {
    const { AstSerializer } = services;

    AstSerializer.registerNodeSerializer(SearchSection, (ctx) => printSearchSection(ctx));
    AstSerializer.registerNodeSerializer(MutationsBlock, (ctx) => printMutationsBlock(ctx));
    AstSerializer.registerNodeSerializer(CrossoversBlock, (ctx) => printCrossoversBlock(ctx));
    AstSerializer.registerNodeSerializer(UsingPath, (ctx) => printUsingPath(ctx));
    AstSerializer.registerNodeSerializer(OperatorArguments, (ctx) => printOperatorArguments(ctx));
    AstSerializer.registerNodeSerializer(OperatorArgument, (ctx) => printOperatorArgument(ctx));
    AstSerializer.registerNodeSerializer(IntLiteral, (ctx) => printIntLiteral(ctx));
    AstSerializer.registerNodeSerializer(LongLiteral, ({ ctx }) => ctx.value);
    AstSerializer.registerNodeSerializer(FloatLiteral, ({ ctx }) => ctx.value);
    AstSerializer.registerNodeSerializer(DoubleLiteral, ({ ctx }) => ctx.value);
    AstSerializer.registerNodeSerializer(StringLiteral, (ctx) => printStringLiteral(ctx));
    AstSerializer.registerNodeSerializer(BooleanLiteral, ({ ctx }) => ctx.value);
    AstSerializer.registerNodeSerializer(NullLiteral, () => "null");
    AstSerializer.registerNodeSerializer(EnumLiteral, (ctx) => printEnumLiteral(ctx));
    AstSerializer.registerNodeSerializer(ListLiteral, (ctx) => printListLiteral(ctx));
    AstSerializer.registerNodeSerializer(CrossoverBlock, (ctx) => printCrossoverBlock(ctx));
    AstSerializer.registerNodeSerializer(ClassMutation, (ctx) => printClassMutation(ctx));
    AstSerializer.registerNodeSerializer(EdgeMutation, (ctx) => printEdgeMutation(ctx));
    AstSerializer.registerNodeSerializer(SolverSection, (ctx) => printSolverSection(ctx));
    AstSerializer.registerNodeSerializer(AlgorithmParameters, (ctx) => printAlgorithmParameters(ctx));
    AstSerializer.registerNodeSerializer(TerminationBlock, (ctx) => printTerminationBlock(ctx));
    AstSerializer.registerNodeSerializer(MutationBlock, (ctx) => printMutationBlock(ctx));
    AstSerializer.registerNodeSerializer(ArchiveBlock, (ctx) => printArchiveBlock(ctx));
    AstSerializer.registerNodeSerializer(MutationStepNumeric, (ctx) => printMutationStepNumeric(ctx));
    AstSerializer.registerNodeSerializer(MutationStepFixed, () => "fixed");
    AstSerializer.registerNodeSerializer(MutationStepFixedN, (ctx) => printMutationStepFixedN(ctx));
    AstSerializer.registerNodeSerializer(MutationStepInterval, (ctx) => printMutationStepInterval(ctx));
    AstSerializer.registerNodeSerializer(RuntimeSection, (ctx) => printRuntimeSection(ctx));
    AstSerializer.registerNodeSerializer(RuntimeTimeoutBlock, (ctx) => printRuntimeTimeoutBlock(ctx));
    AstSerializer.registerNodeSerializer(RuntimeResourcesBlock, (ctx) => printRuntimeResourcesBlock(ctx));
}

/**
 * Prints a search section content.
 *
 * @param context The print context
 * @returns The formatted search section content
 */
function printSearchSection(context: PrintContext<SearchSectionType>): Doc {
    const { ctx, path, print } = context;
    const docs: Doc[] = [];

    docs.push("{");

    const contentDocs: Doc[] = [];
    if (ctx.mutations.length > 0) {
        contentDocs.push(path.call(print, "mutations", 0));
    }
    if (ctx.crossovers.length > 0) {
        contentDocs.push(path.call(print, "crossovers", 0));
    }
    if (contentDocs.length > 0) {
        docs.push(indent([hardline, join(hardline, contentDocs)]));
        docs.push(hardline);
    }

    docs.push("}");

    return group(docs);
}

/**
 * Prints the solver section content.
 *
 * @param context The print context
 * @returns The formatted solver section content
 */
function printSolverSection(context: PrintContext<SolverSectionType>): Doc {
    const { ctx, path, print, printPrimitive } = context;
    const docs: Doc[] = [];
    docs.push("{");

    const contentDocs: Doc[] = [];
    if (ctx.algorithm.length > 0) {
        contentDocs.push(["algorithm = ", ctx.algorithm[0]]);
    }
    if (ctx.parameters.length > 0) {
        contentDocs.push(path.call(print, "parameters", 0));
    }
    if (ctx.termination.length > 0) {
        contentDocs.push(path.call(print, "termination", 0));
    }
    if (ctx.batches.length > 0) {
        contentDocs.push(["batches = ", printPrimitive({ value: ctx.batches[0] }, INT)]);
    }

    if (contentDocs.length > 0) {
        docs.push(indent([hardline, doc.builders.join(hardline, contentDocs)]));
        docs.push(hardline);
    }
    docs.push("}");
    return group(docs);
}

/**
 * Prints the parameters block.
 *
 * @param context The print context
 * @returns The formatted parameters block
 */
function printAlgorithmParameters(context: PrintContext<AlgorithmParametersType>): Doc {
    const { ctx, path, print, printPrimitive } = context;
    const docs: Doc[] = [];
    docs.push("parameters {");

    const contentDocs: Doc[] = [];
    if (ctx.population.length > 0)
        contentDocs.push(["population = ", printPrimitive({ value: ctx.population[0] }, INT)]);
    if (ctx.variation.length > 0) contentDocs.push(["variation = ", ctx.variation[0]]);
    if (ctx.crossover.length > 0) contentDocs.push(path.call(print, "crossover", 0));
    if (ctx.mutation.length > 0) contentDocs.push(path.call(print, "mutation", 0));
    if (ctx.bisections.length > 0)
        contentDocs.push(["bisections = ", printPrimitive({ value: ctx.bisections[0] }, INT)]);
    if (ctx.archive.length > 0) contentDocs.push(path.call(print, "archive", 0));

    if (contentDocs.length > 0) {
        docs.push(indent([hardline, doc.builders.join(hardline, contentDocs)]));
        docs.push(hardline);
    }
    docs.push("}");
    return group(docs);
}

/**
 * Prints the termination block.
 *
 * @param context The print context
 * @returns The formatted termination block
 */
function printTerminationBlock(context: PrintContext<TerminationBlockType>): Doc {
    const { ctx, printPrimitive } = context;
    const docs: Doc[] = [];
    docs.push("termination {");

    const contentDocs: Doc[] = [];
    if (ctx.evolutions.length > 0)
        contentDocs.push(["evolutions = ", printPrimitive({ value: ctx.evolutions[0] }, INT)]);
    if (ctx.time.length > 0) contentDocs.push(["time = ", printPrimitive({ value: ctx.time[0] }, INT)]);
    if (ctx.delta.length > 0) contentDocs.push(["delta = ", printPrimitive({ value: ctx.delta[0] }, INT)]);
    if (ctx.iterations.length > 0)
        contentDocs.push(["iterations = ", printPrimitive({ value: ctx.iterations[0] }, INT)]);

    if (contentDocs.length > 0) {
        docs.push(indent([hardline, doc.builders.join(hardline, contentDocs)]));
        docs.push(hardline);
    }
    docs.push("}");
    return group(docs);
}

/**
 * Prints the mutation sub-block.
 *
 * @param context The print context
 * @returns The formatted mutation block
 */
function printMutationBlock(context: PrintContext<MutationBlockType>): Doc {
    const { ctx, path, print } = context;
    const docs: Doc[] = [];
    docs.push("mutation {");

    const contentDocs: Doc[] = [];
    if (ctx.step.length > 0) contentDocs.push(["step = ", path.call(print, "step", 0)]);
    if (ctx.strategy.length > 0) contentDocs.push(["strategy = ", ctx.strategy[0]]);
    if (ctx.selection.length > 0) contentDocs.push(["selection = ", ctx.selection[0]]);
    if (ctx.application.length > 0) contentDocs.push(["application = ", ctx.application[0]]);
    if (ctx.credit.length > 0) contentDocs.push(["credit = ", ctx.credit[0]]);
    if (ctx.repair.length > 0) contentDocs.push(["repair = ", ctx.repair[0]]);

    if (contentDocs.length > 0) {
        docs.push(indent([hardline, doc.builders.join(hardline, contentDocs)]));
        docs.push(hardline);
    }
    docs.push("}");
    return group(docs);
}

/**
 * Prints the crossover sub-block.
 *
 * @param context The print context
 * @returns The formatted crossover block
 */
function printCrossoverBlock(context: PrintContext<CrossoverBlockType>): Doc {
    const { ctx } = context;
    const docs: Doc[] = [];
    docs.push("crossover {");

    const contentDocs: Doc[] = [];
    if (ctx.rate.length > 0) contentDocs.push(["rate = ", printKeyText(context, "rate", ctx.rate[0])]);
    if (ctx.parents.length > 0) contentDocs.push(["parents = ", printKeyText(context, "parents", ctx.parents[0])]);
    if (ctx.strategy.length > 0) contentDocs.push(["strategy = ", ctx.strategy[0]]);

    if (contentDocs.length > 0) {
        docs.push(indent([hardline, join(hardline, contentDocs)]));
        docs.push(hardline);
    }
    docs.push("}");
    return group(docs);
}

/**
 * Prints the first value of a numeric key as it is written (e.g. `0.90` stays `0.90`).
 *
 * @param context The print context
 * @param property The property of the key
 * @param value The value, printed when the source text is not available
 * @returns The value's text
 */
function printKeyText<T extends AstNode>(context: PrintContext<T>, property: keyof T & string, value: number): Doc {
    const { ctx, printPrimitive, getPrimitive } = context;
    const primitive = getPrimitive(ctx, property);
    return primitive.cstNode?.text ?? printPrimitive({ value }, Number.isInteger(value) ? INT : FLOAT);
}

/**
 * Prints the archive sub-block (PESA2/PAES only).
 *
 * @param context The print context
 * @returns The formatted archive block
 */
function printArchiveBlock(context: PrintContext<ArchiveBlockType>): Doc {
    const { ctx, printPrimitive } = context;
    const docs: Doc[] = [];
    docs.push("archive {");

    if (ctx.size.length > 0) {
        docs.push(indent([hardline, ["size = ", printPrimitive({ value: ctx.size[0] }, INT)]]));
        docs.push(hardline);
    }
    docs.push("}");
    return group(docs);
}

/**
 * Prints a numeric step size.
 * Example: 3
 *
 * @param context The print context
 * @returns The formatted numeric step
 */
function printMutationStepNumeric(context: PrintContext<MutationStepNumericType>): Doc {
    const { ctx, printPrimitive, getPrimitive } = context;
    return printPrimitive(getPrimitive(ctx, "value"), INT);
}

/**
 * Prints a fixed(N) step size.
 * Example: fixed(3)
 *
 * @param context The print context
 * @returns The formatted fixed(N) step
 */
function printMutationStepFixedN(context: PrintContext<MutationStepFixedNType>): Doc {
    const { ctx, printPrimitive, getPrimitive } = context;
    return ["fixed(", printPrimitive(getPrimitive(ctx, "n"), INT), ")"];
}

/**
 * Prints an interval(lower, upper) step size.
 * Example: interval(1, 5)
 *
 * @param context The print context
 * @returns The formatted interval step
 */
function printMutationStepInterval(context: PrintContext<MutationStepIntervalType>): Doc {
    const { ctx, printPrimitive, getPrimitive } = context;
    return [
        "interval(",
        printPrimitive(getPrimitive(ctx, "lower"), INT),
        ", ",
        printPrimitive(getPrimitive(ctx, "upper"), INT),
        ")"
    ];
}

/**
 * Prints a mutations block.
 *
 * @param context The print context
 * @returns The formatted mutations block
 */
function printMutationsBlock(context: PrintContext<MutationsBlockType>): Doc {
    const docs: Doc[] = [];

    docs.push("mutations {");

    const content = serializeNewlineSep(context, ["usingPaths", "classMutations", "edgeMutations"], doc.builders);
    if (content.length > 0) {
        docs.push(indent([hardline, content]));
        docs.push(hardline);
    }

    docs.push("}");

    return group(docs);
}

/**
 * Prints a crossovers block.
 *
 * @param context The print context
 * @returns The formatted crossovers block
 */
function printCrossoversBlock(context: PrintContext<CrossoversBlockType>): Doc {
    const docs: Doc[] = [];

    docs.push("crossovers {");

    const content = serializeNewlineSep(context, ["usingPaths"], doc.builders);
    if (content.length > 0) {
        docs.push(indent([hardline, content]));
        docs.push(hardline);
    }

    docs.push("}");

    return group(docs);
}

/**
 * Prints a using path declaration, with its arguments block if it has one.
 *
 * @param context The print context
 * @returns The formatted using path
 */
function printUsingPath(context: PrintContext<UsingPathType>): Doc {
    const { printPrimitive, getPrimitive, ctx, path, print } = context;
    const usingDoc: Doc[] = ["using ", printPrimitive(getPrimitive(ctx, "path"), STRING)];
    if (ctx.with != undefined) {
        usingDoc.push(" ", path.call(print, "with"));
    }
    return usingDoc;
}

/**
 * Prints the arguments block of a using entry, one argument per line.
 *
 * @param context The print context
 * @returns The formatted `with { … }` block
 */
function printOperatorArguments(context: PrintContext<OperatorArgumentsType>): Doc {
    const docs: Doc[] = ["with {"];
    const content = serializeNewlineSep(context, ["arguments"], doc.builders);
    if (content.length > 0) {
        docs.push(indent([hardline, content]));
        docs.push(hardline);
    } else {
        docs.push(" ");
    }
    docs.push("}");
    return docs;
}

/**
 * Prints an operator argument: `name = literal`.
 *
 * @param context The print context
 * @returns The formatted argument
 */
function printOperatorArgument(context: PrintContext<OperatorArgumentType>): Doc {
    const { ctx, getPrimitive, path, print } = context;
    const name = getPrimitive(ctx, "name").cstNode?.text ?? ctx.name;
    return [name, " = ", path.call(print, "value")];
}

/**
 * Prints an int literal as written.
 *
 * @param context The print context
 * @returns The formatted literal
 */
function printIntLiteral(context: PrintContext<IntLiteralType>): Doc {
    const { ctx, printPrimitive, getPrimitive } = context;
    return printPrimitive(getPrimitive(ctx, "value"), INT);
}

/**
 * Prints a string literal.
 *
 * @param context The print context
 * @returns The formatted literal
 */
function printStringLiteral(context: PrintContext<StringLiteralType>): Doc {
    const { ctx, printPrimitive, getPrimitive } = context;
    return printPrimitive(getPrimitive(ctx, "value"), STRING);
}

/**
 * Prints an enum literal: `Enum.ENTRY`.
 *
 * @param context The print context
 * @returns The formatted literal
 */
function printEnumLiteral(context: PrintContext<EnumLiteralType>): Doc {
    const { path, printReference } = context;
    return [
        path.call((ref) => printReference(ref, ID), "enum"),
        ".",
        path.call((ref) => printReference(ref, ID), "entry")
    ];
}

/**
 * Prints a list literal on one line, or one element per line when it does not fit.
 *
 * @param context The print context
 * @returns The formatted literal
 */
function printListLiteral(context: PrintContext<ListLiteralType>): Doc {
    const { ctx, path, print } = context;
    if (ctx.elements.length === 0) {
        return "[]";
    }
    return group(["[", indent([softline, join([",", line], path.map(print, "elements"))]), softline, "]"]);
}

/**
 * Prints a class mutation declaration.
 *
 * @param context The print context
 * @returns The formatted class mutation
 */
function printClassMutation(context: PrintContext<ClassMutationType>): Doc {
    const { ctx, path, printReference } = context;
    return [ctx.operator, " ", path.call((ref) => printReference(ref, ID), "class")];
}

/**
 * Prints an edge mutation declaration.
 *
 * @param context The print context
 * @returns The formatted edge mutation
 */
function printEdgeMutation(context: PrintContext<EdgeMutationType>): Doc {
    const { ctx, path, printReference } = context;
    return [
        ctx.operator,
        " ",
        path.call((ref) => printReference(ref, ID), "class"),
        ".",
        path.call((ref) => printReference(ref, ID), "edge")
    ];
}

/**
 * Prints a runtime section content.
 *
 * @param context The print context
 * @returns The formatted runtime section content
 */
function printRuntimeSection(context: PrintContext<RuntimeSectionType>): Doc {
    const { ctx, path, print } = context;
    const docs: Doc[] = [];
    docs.push("{");

    const contentDocs: Doc[] = [];
    if (ctx.timeout.length > 0) {
        contentDocs.push(path.call(print, "timeout", 0));
    }
    if (ctx.backend.length > 0) {
        contentDocs.push(["backend = ", ctx.backend[0]]);
    }
    if (ctx.resources.length > 0) {
        contentDocs.push(path.call(print, "resources", 0));
    }

    if (contentDocs.length > 0) {
        docs.push(indent([hardline, doc.builders.join(hardline, contentDocs)]));
        docs.push(hardline);
    }
    docs.push("}");
    return group(docs);
}

/**
 * Prints a runtime timeout block.
 *
 * @param context The print context
 * @returns The formatted timeout block
 */
function printRuntimeTimeoutBlock(context: PrintContext<RuntimeTimeoutBlockType>): Doc {
    const { ctx, printPrimitive } = context;
    const docs: Doc[] = [];
    docs.push("timeout {");

    const contentDocs: Doc[] = [];
    if (ctx.script.length > 0) {
        contentDocs.push(["script = ", printPrimitive({ value: ctx.script[0] }, INT)]);
    }
    if (ctx.transformation.length > 0) {
        contentDocs.push(["transformation = ", printPrimitive({ value: ctx.transformation[0] }, INT)]);
    }

    if (contentDocs.length > 0) {
        docs.push(indent([hardline, doc.builders.join(hardline, contentDocs)]));
        docs.push(hardline);
    }
    docs.push("}");
    return group(docs);
}

/**
 * Prints a runtime resources block.
 *
 * @param context The print context
 * @returns The formatted resources block
 */
function printRuntimeResourcesBlock(context: PrintContext<RuntimeResourcesBlockType>): Doc {
    const { ctx, printPrimitive } = context;
    const docs: Doc[] = [];
    docs.push("resources {");

    const contentDocs: Doc[] = [];
    if (ctx.threads.length > 0) {
        contentDocs.push(["threads = ", printPrimitive({ value: ctx.threads[0] }, INT)]);
    }
    if (ctx.nodes.length > 0) {
        contentDocs.push(["nodes = ", printPrimitive({ value: ctx.nodes[0] }, INT)]);
    }
    if (ctx.threadsPerNode.length > 0) {
        contentDocs.push(["threadsPerNode = ", printPrimitive({ value: ctx.threadsPerNode[0] }, INT)]);
    }

    if (contentDocs.length > 0) {
        docs.push(indent([hardline, doc.builders.join(hardline, contentDocs)]));
        docs.push(hardline);
    }
    docs.push("}");
    return group(docs);
}
