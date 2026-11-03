import type { Type, TypirSpecifics } from "typir";
import type { Scope, ScopeEntry } from "./scope.js";
import type { FunctionType, Member, ValueType } from "../config/type.js";
import type { ExtendedTypirServices } from "../service/extendedTypirServices.js";

/**
 * Represents the global scope containing top-level declarations and built-in members.
 *
 * This scope implementation provides access to globally available functions and properties
 * that are accessible throughout the entire program without requiring explicit imports or
 * parent scope lookups.
 *
 * @template Specifics The type-specific configuration extending TypirSpecifics
 */
export class GlobalScope<Specifics extends TypirSpecifics> implements Scope<Specifics> {
    /**
     * Map of member names to their corresponding scope entries.
     */
    private readonly entriesMap: Map<string, ScopeEntry<Specifics>> = new Map();

    /**
     * Stored reference to the Typir services, used for resolving types in addEntry.
     */
    private readonly typir: ExtendedTypirServices<Specifics>;

    readonly executionGuaranteedUntil: number | undefined = undefined;

    /**
     * Creates a new global scope with the specified member entries.
     *
     * Initializes the scope by resolving types for all provided members. Properties are
     * resolved as custom class or lambda types, while functions are resolved as custom
     * function types.
     *
     * @param entries Array of members (functions and properties) to be available in the global scope
     * @param typir Extended Typir services for type resolution and management
     */
    constructor(
        protected readonly entries: Member[],
        typir: ExtendedTypirServices<Specifics>
    ) {
        this.typir = typir;
        for (const entry of entries) {
            this.resolveAndAdd(entry);
        }
    }

    /**
     * Dynamically adds a new entry to the global scope after construction.
     * The type referenced by the entry must already be registered in the type definitions.
     *
     * @param entry The member to add to the global scope
     */
    addEntry(entry: Member): void {
        this.resolveAndAdd(entry);
    }

    /**
     * Resolves the type for a member and stores it in the entries map.
     *
     * @param entry The member whose type should be resolved and added
     */
    private resolveAndAdd(entry: Member): void {
        let type: Type;
        if (entry.isProperty) {
            type = this.typir.TypeDefinitions.resolveCustomClassOrLambdaType(entry.type as ValueType);
        } else {
            type = this.typir.TypeDefinitions.resolveCustomFunctionType(entry.type as FunctionType, entry.name);
        }
        const scopeEntry: ScopeEntry<Specifics> = {
            name: entry.name,
            definingScope: this,
            position: -1,
            inferType: () => type,
            readonly: entry.isProperty ? entry.readonly : undefined
        };
        this.entriesMap.set(entry.name, scopeEntry);
    }

    getEntry(name: string): ScopeEntry<Specifics> | undefined {
        return this.entriesMap.get(name);
    }

    isEntryInitialized(entry: ScopeEntry<Specifics>): boolean {
        return entry.definingScope === this;
    }

    getEntries(): ScopeEntry<Specifics>[] {
        return [...this.entriesMap.values()];
    }

    get level(): number {
        return 0;
    }

    get languageNode(): Specifics["LanguageType"] | undefined {
        return undefined;
    }
}
