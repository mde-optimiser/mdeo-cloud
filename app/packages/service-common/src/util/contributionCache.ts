import type { ServerContributionPlugin } from "@mdeo/plugin";

/**
 * Header this service answers with to tell the backend it accepts a contribution hash in place of
 * the contribution payloads.
 */
export const CONTRIBUTION_HASH_SUPPORT_HEADER = "x-mdeo-contribution-hashes";

/**
 * Header on a `409` answer telling the backend this service does not hold the contribution set a
 * hash stands for, so the request has to be sent again with the payloads.
 */
export const CONTRIBUTIONS_UNKNOWN_HEADER = "x-mdeo-contributions-unknown";

/**
 * How many contribution sets a service remembers. One project uses one set per language, so this
 * covers many projects at once; a set that was forgotten is simply sent again.
 */
const DEFAULT_MAX_SETS = 64;

/**
 * The contribution sets this service was sent, by the hash the backend identifies them with.
 *
 * The backend sends a language's contribution plugins with every request. Once it knows a service
 * keeps them, it sends only their hash; the service looks the set up here, or answers that it does
 * not hold it.
 */
export class ContributionCache {
    private readonly sets = new Map<string, ServerContributionPlugin[]>();

    /**
     * @param maxSets How many sets to remember, the least recently used being forgotten first
     */
    constructor(private readonly maxSets: number = DEFAULT_MAX_SETS) {}

    /**
     * Resolves the contribution plugins of one request.
     *
     * @param plugins The payloads, when the request carries them
     * @param hash The hash of the set, when the backend sent one
     * @returns The contribution plugins, or undefined when the request carries only a hash this
     *          service does not hold
     */
    resolve(plugins: object[] | undefined, hash: string | undefined): ServerContributionPlugin[] | undefined {
        if (plugins != undefined) {
            const resolved = plugins as unknown as ServerContributionPlugin[];
            if (hash != undefined) {
                this.remember(hash, resolved);
            }
            return resolved;
        }
        if (hash == undefined) {
            return [];
        }
        const known = this.sets.get(hash);
        if (known != undefined) {
            // Refresh its position so the least recently used set is forgotten first.
            this.sets.delete(hash);
            this.sets.set(hash, known);
        }
        return known;
    }

    private remember(hash: string, plugins: ServerContributionPlugin[]): void {
        this.sets.delete(hash);
        this.sets.set(hash, plugins);
        while (this.sets.size > this.maxSets) {
            const oldest = this.sets.keys().next().value;
            if (oldest == undefined) {
                break;
            }
            this.sets.delete(oldest);
        }
    }
}
