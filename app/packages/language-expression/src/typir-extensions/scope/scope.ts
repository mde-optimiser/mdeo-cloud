import type { InferenceProblem, Type, TypirSpecifics } from "typir";

/**
 * A scope in the Typir type inference system.
 */
export interface Scope<Specifics extends TypirSpecifics> {
    /**
     * The language AST node that this scope is associated with.
     */
    readonly languageNode?: Specifics["LanguageType"];

    /**
     * The level of this scope in the scope hierarchy.
     * 0 represents the global scope, and each nested scope increments this value.
     */
    readonly level: number;

    /**
     * Gets the scope entry with the given name at the given position.
     * Utilizes lexical scoping rules.
     *
     * @param name the name of the scope entry
     * @param position the position at which to look for the scope entry
     * @returns the scope entry, or undefined if not found
     */
    getEntry(name: string, position: number): ScopeEntry<Specifics> | undefined;

    /**
     * Checks if the given scope entry is initialized at the given position.
     *
     * @param entry the scope entry
     * @param position the position to check
     * @returns true if the entry is initialized at the position, false otherwise
     */
    isEntryInitialized(entry: ScopeEntry<Specifics>, position: number): boolean;

    /**
     * Gets all scope entries visible at the given position.
     *
     * @param position the position to get entries for
     * @returns the list of scope entries
     */
    getEntries(position: number): ScopeEntry<Specifics>[];
}

/**
 * A scope that is bound to a specific position.
 */
export interface BoundScope<Specifics extends TypirSpecifics> {
    /**
     * The underlying scope.
     */
    readonly scope: Scope<Specifics>;

    /**
     * Gets the scope entry with the given name at the given position.
     * Utilizes lexical scoping rules.
     *
     * @param name the name of the scope entry
     * @returns the scope entry, or undefined if not found
     */
    getEntry(name: string): ScopeEntry<Specifics> | undefined;

    /**
     * Checks if the given scope entry is initialized at the given position.
     *
     * @param entry the scope entry
     * @returns true if the entry is initialized at the position, false otherwise
     */
    isEntryInitialized(entry: ScopeEntry<Specifics>): boolean;

    /**
     * Gets all scope entries visible at the given position.
     * Guaranteed to be ordered by position.
     *
     * @returns the list of scope entries
     */
    getEntries(): ScopeEntry<Specifics>[];
}

/**
 * An entry in a scope.
 */
export interface ScopeEntry<Specifics extends TypirSpecifics> {
    /**
     * The name of the scope entry.
     */
    name: string;

    /**
     * The scope in which the entry is defined.
     */
    definingScope: Scope<Specifics>;

    /**
     * The position at which the entry is defined in the defining scope.
     */
    position: number;

    /**
     * Infers the type of the scope entry.
     */
    inferType(): Type | Array<InferenceProblem<Specifics>>;

    /**
     * The language AST node that defines this scope entry.
     */
    languageNode?: Specifics["LanguageType"];

    /**
     * Whether this property is readonly (only applicable for properties)
     */
    readonly?: boolean;
}

/**
 * Initialization information for a scope entry in a local scope.
 */
export interface ScopeLocalInitialization {
    /**
     * The name of the scope entry being initialized.
     */
    name: string;
    /**
     * The position at which the entry is initialized.
     */
    position: number;
}

/**
 * The default implementation of a scope.
 */
export class DefaultScope<Specifics extends TypirSpecifics> implements Scope<Specifics> {
    /**
     * The position at which each entry is initialized in this scope, -1 for the start of the scope.
     * This can include entries defined in parent scopes.
     */
    private readonly initializationLookup: Map<ScopeEntry<Specifics>, number> = new Map();

    /**
     * A lookup for local entries by name.
     */
    private readonly localEntryLookup: Map<string, ScopeEntry<Specifics>> = new Map();

    /**
     * Creates a new DefaultScope.
     *
     * @param parent the optional parent scope
     * @param entriesProvider provider for the local scope entries
     * @param localInitializations the positions at which entries are initialized in this scope
     * @param languageNode the language AST node that this scope is associated with
     */
    constructor(
        private readonly parent: BoundScope<Specifics> | undefined,
        entriesProvider: (scope: Scope<Specifics>) => ScopeEntry<Specifics>[],
        localInitializations: ScopeLocalInitialization[],
        readonly languageNode: Specifics["LanguageType"] | undefined
    ) {
        for (const entry of entriesProvider(this)) {
            this.localEntryLookup.set(entry.name, entry);
        }
        for (const init of localInitializations) {
            const entry = this.getEntry(init.name, init.position);
            const current = entry != undefined ? this.initializationLookup.get(entry) : undefined;
            if (entry != undefined && (current == undefined || init.position < current)) {
                this.initializationLookup.set(entry, init.position);
            }
        }
    }

    get level(): number {
        return this.parent != undefined ? this.parent.scope.level + 1 : 0;
    }

    getEntry(name: string, position: number): ScopeEntry<Specifics> | undefined {
        const localEntry = this.localEntryLookup.get(name);
        if (localEntry != undefined && localEntry.position <= position) {
            return localEntry;
        }
        if (this.parent != undefined) {
            return this.parent.getEntry(name);
        }
        return undefined;
    }

    isEntryInitialized(entry: ScopeEntry<Specifics>, position: number): boolean {
        const initializedAt = this.initializationLookup.get(entry);
        if (initializedAt != undefined && initializedAt <= position) {
            return true;
        }
        if (entry.definingScope === this || this.parent == undefined) {
            return false;
        }
        return this.parent.isEntryInitialized(entry);
    }

    getEntries(position: number): ScopeEntry<Specifics>[] {
        const localEntries = [...this.localEntryLookup.values()]
            .filter((entry) => entry.position <= position)
            .sort((a, b) => a.position - b.position);
        if (this.parent != undefined) {
            const parentEntries = this.parent.getEntries();
            return [...localEntries, ...parentEntries];
        } else {
            return localEntries;
        }
    }
}

/**
 * The default implementation of a bound scope.
 */
export class DefaultBoundScope<Specifics extends TypirSpecifics> implements BoundScope<Specifics> {
    /**
     * Creates a new DefaultBoundScope.
     *
     * @param scope the underlying scope
     * @param position the position to bind the scope to
     */
    constructor(
        readonly scope: Scope<Specifics>,
        private readonly position: number
    ) {}

    getEntry(name: string): ScopeEntry<Specifics> | undefined {
        return this.scope.getEntry(name, this.position);
    }

    isEntryInitialized(entry: ScopeEntry<Specifics>): boolean {
        return this.scope.isEntryInitialized(entry, this.position);
    }

    getEntries(): ScopeEntry<Specifics>[] {
        return this.scope.getEntries(this.position);
    }
}
