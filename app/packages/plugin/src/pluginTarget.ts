/**
 * The kinds of thing a plugin capability can be addressed on.
 *
 * Both language plugins and contribution plugins already carry ids, and those id spaces
 * overlap — `config-mdeo` names both a language and a contribution. Every address therefore
 * spells out which of the two it means.
 */
export const PluginTargetKind = {
    /**
     * A language plugin, addressed by its language id.
     */
    LANGUAGE: "lang",
    /**
     * A server contribution plugin, addressed by its contribution id.
     */
    CONTRIBUTION: "contrib"
} as const;

/**
 * One of the two kinds a {@link PluginTarget} can have.
 */
export type PluginTargetKind = (typeof PluginTargetKind)[keyof typeof PluginTargetKind];

/**
 * A plugin capability target: a kind and the id of a thing of that kind.
 */
export interface PluginTarget {
    /**
     * Whether the id names a language or a contribution.
     */
    kind: PluginTargetKind;
    /**
     * The language id or contribution id.
     */
    id: string;
}

/**
 * Characters an id may consist of. Ids travel through URL segments, JWT claims and log lines,
 * so they are kept to a set that needs no escaping in any of them.
 */
const TARGET_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_.-]*$/;

/**
 * Checks whether a string is one of the two known target kinds.
 *
 * @param value The string to test
 * @returns True when the string is a target kind
 */
export function isPluginTargetKind(value: string): value is PluginTargetKind {
    return value === PluginTargetKind.LANGUAGE || value === PluginTargetKind.CONTRIBUTION;
}

/**
 * Builds a target from its two parts, as they arrive in separate URL segments.
 *
 * @param kind The kind, e.g. `lang`
 * @param id The language or contribution id
 * @returns The target, or undefined when the kind is unknown or the id unusable
 */
export function pluginTargetOf(kind: string, id: string): PluginTarget | undefined {
    if (!isPluginTargetKind(kind) || !TARGET_ID_PATTERN.test(id)) {
        return undefined;
    }
    return { kind, id };
}

/**
 * Renders a target as the single string used everywhere one is written down — URL segments,
 * the `target` claim of a session token, configuration keys, log lines and error messages.
 *
 * @param target The target to render
 * @returns The address, e.g. `lang:script` or `contrib:script-functions`
 * @throws If the id is empty or contains characters an address may not carry
 */
export function formatPluginTarget(target: PluginTarget): string {
    if (!TARGET_ID_PATTERN.test(target.id)) {
        throw new Error(`Invalid plugin target id '${target.id}'`);
    }
    return `${target.kind}:${target.id}`;
}

/**
 * Parses an address produced by {@link formatPluginTarget}.
 *
 * @param address The address to parse
 * @returns The parsed target
 * @throws If the address names no known kind or carries an unusable id
 */
export function parsePluginTarget(address: string): PluginTarget {
    const separator = address.indexOf(":");
    if (separator < 0) {
        throw new Error(`Invalid plugin target '${address}': expected '<kind>:<id>'`);
    }
    const kind = address.substring(0, separator);
    const id = address.substring(separator + 1);
    if (!isPluginTargetKind(kind)) {
        throw new Error(
            `Invalid plugin target '${address}': unknown kind '${kind}', expected ` +
                `'${PluginTargetKind.LANGUAGE}' or '${PluginTargetKind.CONTRIBUTION}'`
        );
    }
    if (!TARGET_ID_PATTERN.test(id)) {
        throw new Error(`Invalid plugin target '${address}': unusable id '${id}'`);
    }
    return { kind, id };
}
