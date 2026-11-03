import { createTerminal } from "../grammar/rule/terminal/factory.js";

/**
 * Identifier token
 */
export const ID = createTerminal("ID")
    .returns(String)
    .as(/`[^`\n\r]+`|[\p{ID_Start}][\p{ID_Continue}]*/u);

/**
 * Integer token
 */
export const INT = createTerminal("INT")
    .returns(Number)
    .as(/-?[0-9]+/);

/**
 * Float token: a fraction (`1.5`), a fraction with an exponent (`1.0e300`, `2.5E-3`) or an
 * exponent alone (`1e3`), as JavaScript prints numbers
 */
export const FLOAT = createTerminal("FLOAT")
    .returns(Number)
    .as(/-?[0-9]+(?:\.[0-9]+(?:[eE][+-]?[0-9]+)?|[eE][+-]?[0-9]+)/);

/**
 * String token
 */
export const STRING = createTerminal("STRING")
    .returns(String)
    .as(/"([^"\\\n]|\\(["\\nt]|u[0-9a-fA-F]{4}))*"/);

/**
 * Whitespace token
 */
export const WS = createTerminal("WS")
    .hidden()
    .as(/[^\S\n]+/);

/**
 * Newline token which not hidden
 */
export const NEWLINE = createTerminal("NEWLINE").as(/(\r\n|\r|\n)/);

/**
 * Newline token which is hidden
 */
export const HIDDEN_NEWLINE = createTerminal("SKIPPED_NEWLINE")
    .hidden()
    .as(/(\r\n|\r|\n)/);

/**
 * Multi-line comment token
 */
export const ML_COMMENT = createTerminal("ML_COMMENT")
    .hidden()
    .as(/\/\*[\s\S]*?\*\//);

/**
 *  Single-line comment token
 */
export const SL_COMMENT = createTerminal("SL_COMMENT")
    .hidden()
    .as(/\/\/[^\n\r]*/);
