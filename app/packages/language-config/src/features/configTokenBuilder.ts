import type { GrammarAST, Stream } from "langium";
import type { TokenType } from "chevrotain";
import { NewlineAwareTokenBuilder } from "@mdeo/language-shared";

/**
 * Token builder of the config language and its contribution languages.
 *
 * The terminals of a config grammar come from several contribution plugins, in the order in which
 * the composed grammar reaches them, and the lexer tries them in that order. A terminal whose
 * pattern extends the pattern of another one (`-?[0-9]+[Ll]` extends `-?[0-9]+`, as do the
 * floating point literals) would never match if the shorter one is tried first, so it is made a
 * longer alternative of the shorter one: the lexer takes it whenever it matches more text.
 */
export class ConfigTokenBuilder extends NewlineAwareTokenBuilder {
    protected override buildTerminalTokens(rules: Stream<GrammarAST.AbstractRule>): TokenType[] {
        const tokens = super.buildTerminalTokens(rules);
        for (const token of tokens) {
            const source = patternSource(token);
            if (source == undefined) {
                continue;
            }
            const longerAlts = tokens.filter((other) => {
                const otherSource = patternSource(other);
                return (
                    other !== token &&
                    otherSource != undefined &&
                    otherSource.length > source.length &&
                    otherSource.startsWith(source)
                );
            });
            if (longerAlts.length > 0) {
                token.LONGER_ALT = longerAlts;
            }
        }
        return tokens;
    }
}

/**
 * Returns the regular expression source of a token's pattern, if it is a regular expression.
 */
function patternSource(token: TokenType): string | undefined {
    const pattern = token.PATTERN;
    return pattern instanceof RegExp ? pattern.source : undefined;
}
