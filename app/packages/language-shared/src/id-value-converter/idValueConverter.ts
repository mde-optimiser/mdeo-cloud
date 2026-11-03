import type { CstNode, DefaultValueConverter, ValueType } from "langium";
import { sharedImport } from "../sharedImport.js";

const { DefaultValueConverter: DefaultValueConverterBase } = sharedImport("langium");

/**
 * Value converter that supports backtick-quoted IDs and every escape sequence the STRING
 * terminal accepts (`\"`, `\\`, `\n`, `\t` and `\uXXXX`).
 */
export class IdValueConverter extends DefaultValueConverterBase {
    protected override runConverter(
        rule: Parameters<DefaultValueConverter["runConverter"]>[0],
        input: string,
        cstNode: CstNode
    ): ValueType {
        if (rule.name === "ID") {
            return parseIdentifier(input);
        }
        if (rule.name === "STRING") {
            return parseString(input);
        }
        return super.runConverter(rule, input, cstNode);
    }
}

/**
 * Parses an identifier, removing backticks if present.
 *
 * @param identifier the identifier to parse
 * @returns the parsed identifier without backticks
 */
export function parseIdentifier(identifier: string): string {
    if (identifier.charAt(0) === "`") {
        return identifier.substring(1, identifier.length - 1);
    } else {
        return identifier;
    }
}

/**
 * Parses a string literal: removes the quotes and decodes its escape sequences. Unlike Langium's
 * default, which drops the backslash of an escape it does not know, this decodes `\uXXXX` to
 * the character it stands for.
 *
 * @param literal the string literal, with its quotes
 * @returns the string it stands for
 */
export function parseString(literal: string): string {
    let result = "";
    for (let i = 1; i < literal.length - 1; i++) {
        const c = literal.charAt(i);
        if (c !== "\\") {
            result += c;
            continue;
        }
        const escaped = literal.charAt(++i);
        switch (escaped) {
            case "n":
                result += "\n";
                break;
            case "t":
                result += "\t";
                break;
            case "u": {
                const code = literal.substring(i + 1, i + 5);
                if (/^[0-9a-fA-F]{4}$/.test(code)) {
                    result += String.fromCharCode(parseInt(code, 16));
                    i += 4;
                } else {
                    result += escaped;
                }
                break;
            }
            default:
                result += escaped;
        }
    }
    return result;
}
