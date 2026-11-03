import type { LangiumDocument } from "langium";
import type { Diagnostic, DiagnosticSeverity, Range } from "vscode-languageserver-types";

/**
 * Where a diagnostic of a document comes from.
 */
export type DocumentDiagnosticSource = "lexer" | "parser" | "linking" | "validation";

/**
 * A diagnostic of a document, in a form that can be sent as JSON.
 */
export interface DocumentDiagnostic {
    /**
     * The stage that reported the diagnostic.
     */
    source: DocumentDiagnosticSource;
    /**
     * The LSP severity: 1 error, 2 warning, 3 information, 4 hint.
     */
    severity: DiagnosticSeverity;
    /**
     * The message shown to the user.
     */
    message: string;
    /**
     * The zero-based LSP range the diagnostic applies to.
     */
    range: Range;
    /**
     * The diagnostic code, if the validator gave one.
     */
    code?: string | number;
}

/**
 * The LSP severity of an error. Langium treats a diagnostic without a severity as an error.
 */
const ERROR_SEVERITY: DiagnosticSeverity = 1;

/**
 * The codes Langium's document validator marks lexer, parser and linking diagnostics with.
 */
const DIAGNOSTIC_SOURCES: Record<string, DocumentDiagnosticSource> = {
    "lexing-error": "lexer",
    "parsing-error": "parser",
    "linking-error": "linking"
};

/**
 * Checks if the given document has any parser-level errors (lexer or parser errors only).
 * Deliberately excludes diagnostic-level errors (such as unresolved references) because
 * these are expected in contexts where cross-language references cannot be resolved.
 *
 * @param document The Langium document to check
 * @returns True if the document has lexer or parser errors, false otherwise
 */
export function hasParserErrors(document: LangiumDocument): boolean {
    return document.parseResult.lexerErrors.length > 0 || document.parseResult.parserErrors.length > 0;
}

/**
 * Checks if the given document has any errors: lexer or parser errors, or diagnostics of error
 * severity. Warnings, information and hints do not count.
 *
 * @param document The Langium document to check
 * @returns True if the document has errors, false otherwise
 */
export function hasErrors(document: LangiumDocument): boolean {
    return hasParserErrors(document) || (document.diagnostics?.some(isError) ?? false);
}

/**
 * Collects the diagnostics of a validated document, lexer and parser errors included.
 *
 * Only meant for reporting: checking whether a document has errors is cheaper with {@link hasErrors}.
 *
 * @param document The validated Langium document
 * @returns The diagnostics of the document, in the order the validator reported them
 */
export function collectDiagnostics(document: LangiumDocument): DocumentDiagnostic[] {
    return (document.diagnostics ?? []).map((diagnostic) => {
        const dataCode = (diagnostic.data as { code?: unknown } | undefined)?.code;
        return {
            source: (typeof dataCode === "string" ? DIAGNOSTIC_SOURCES[dataCode] : undefined) ?? "validation",
            severity: diagnostic.severity ?? ERROR_SEVERITY,
            // LSP 3.18 allows markup in a diagnostic message; reports carry its text.
            message: typeof diagnostic.message === "string" ? diagnostic.message : diagnostic.message.value,
            range: diagnostic.range,
            ...(diagnostic.code != undefined && { code: diagnostic.code })
        };
    });
}

/**
 * Describes the errors of the given documents for a user who tried to run something with them,
 * naming each file with errors and where its first error is.
 *
 * @param documents The documents to check
 * @returns The message, or undefined if none of the documents has errors
 */
export function describeValidationErrors(documents: Iterable<LangiumDocument>): string | undefined {
    const lines: string[] = [];
    for (const document of documents) {
        if (!hasErrors(document)) {
            continue;
        }
        const fileName = document.uri.path.split("/").pop() ?? document.uri.path;
        const errors = collectDiagnostics(document).filter((diagnostic) => diagnostic.severity === ERROR_SEVERITY);
        const [first] = errors;
        if (first == undefined) {
            lines.push(`• ${fileName}`);
            continue;
        }
        const more = errors.length > 1 ? ` (+${errors.length - 1} more)` : "";
        lines.push(`• ${fileName}:${first.range.start.line + 1}: ${first.message}${more}`);
    }
    if (lines.length === 0) {
        return undefined;
    }
    return `Cannot start execution. The following files have validation errors:\n${lines.join("\n")}`;
}

/**
 * Checks if a diagnostic is an error.
 *
 * @param diagnostic The diagnostic to check
 * @returns True if the diagnostic has error severity
 */
function isError(diagnostic: Diagnostic): boolean {
    return (diagnostic.severity ?? ERROR_SEVERITY) === ERROR_SEVERITY;
}
