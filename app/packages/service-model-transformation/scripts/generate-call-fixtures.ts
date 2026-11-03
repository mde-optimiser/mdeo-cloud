/* eslint-disable no-console -- a command-line script */
import { mkdirSync, writeFileSync } from "node:fs";
import { TestWorkspace } from "./testWorkspace.ts";
import { layeredProject, SCENARIOS, COLLECTION_SCENARIOS, TRAP_SCENARIOS, MODEL_SCENARIOS } from "./fixtures.ts";

const { typedAstHandler } = await import("../dist/handler/typedAstHandler.js");
const { metamodelAstDataHandler } = await import("../../service-metamodel/dist/handler/metamodelAstDataHandler.js");

/**
 * Writes the typed ASTs of the fixtures (`fixtures.ts`) that the engine tests of callable
 * transformations (platform/model-transformation: CallStatementExecutionTest,
 * CollectionCallExecutionTest, LanguageTrapsExecutionTest, and the tests of model inputs) run,
 * as this package's converter
 * produces them, together with the ASTs of the metamodels they use.
 *
 * Run it with `npm run generate:call-fixtures` (after `npm run build:packages` in `app/`) whenever
 * the fixtures, the grammar or the typed AST change, and commit the JSON files it writes. It fails
 * (and writes nothing for that file) when a fixture does not build without errors.
 */
const FIXTURE_DIR = new URL("../../../../platform/model-transformation/src/test/resources/calls/", import.meta.url)
    .pathname;

const TRANSFORMATIONS = [
    "/optimize/move-with-stores.mt",
    "/optimize/generic/move-component.mt",
    "/optimize/sync/follow-reader.mt",
    ...Object.keys(SCENARIOS).filter((path) => path.includes("/call-")),
    ...Object.keys(COLLECTION_SCENARIOS).filter((path) => path.includes("/call-")),
    ...Object.keys(TRAP_SCENARIOS).filter((path) => path.includes("/call-")),
    "/optimize/scenarios/move-placement.mt",
    ...Object.keys(MODEL_SCENARIOS)
];

const METAMODELS = ["/metamodel/shop.mm", "/metamodel/base.mm", "/metamodel/shapes.mm", "/metamodel/numbers.mm"];

/**
 * The name of the JSON file of a project path, e.g. `optimize__generic__move-component.mt.json`.
 */
function fixtureName(path: string): string {
    return path.replace(/^\//, "").replaceAll("/", "__") + ".json";
}

/**
 * Writes one fixture.
 */
function write(name: string, data: unknown): void {
    mkdirSync(FIXTURE_DIR, { recursive: true });
    writeFileSync(FIXTURE_DIR + name, JSON.stringify(data, null, 1) + "\n");
}

const workspace = new TestWorkspace(
    layeredProject({ ...SCENARIOS, ...COLLECTION_SCENARIOS, ...TRAP_SCENARIOS, ...MODEL_SCENARIOS })
);
const failures: string[] = [];

for (const path of TRANSFORMATIONS) {
    const run = await workspace.run(typedAstHandler, path);
    if (run.errors.length > 0 || run.data == null) {
        failures.push(`${path}:\n    ${run.errors.join("\n    ") || "no typed AST"}`);
        continue;
    }
    write(fixtureName(path), run.data);
}

for (const path of METAMODELS) {
    const run = await workspace.run(metamodelAstDataHandler, path, ".mm");
    if (run.errors.length > 0) {
        failures.push(`${path}:\n    ${run.errors.join("\n    ")}`);
        continue;
    }
    write(fixtureName(path), run.data);
}

if (failures.length > 0) {
    console.error(`Fixtures with errors (not written):\n${failures.join("\n")}`);
    process.exit(1);
}
console.log(`Wrote ${TRANSFORMATIONS.length + METAMODELS.length} fixtures to ${FIXTURE_DIR}`);
