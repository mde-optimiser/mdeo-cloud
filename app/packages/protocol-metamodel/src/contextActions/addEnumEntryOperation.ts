import type { Operation } from "@eclipse-glsp/protocol";
import type { NewLabelOperation } from "@mdeo/protocol-common";

/**
 * Operation to add an enum entry to an enumeration.
 *
 * This is a high-complexity context action that typically creates a new
 * entry label and may create an enum-entry compartment when missing.
 */
export interface AddEnumEntryOperation extends NewLabelOperation {
    /**
     * The operation kind discriminator.
     */
    kind: "addEnumEntry";

    /**
     * The identifier of the enum to update.
     */
    enumId: string;

    /**
     * The entry name to create.
     */
    entryName: string;
}

/**
 * Namespace helpers for {@link AddEnumEntryOperation}.
 */
export namespace AddEnumEntryOperation {
    /**
     * Discriminator constant for this operation type.
     */
    export const KIND = "addEnumEntry";

    /**
     * Identifier carried in `newLabelOperationKind` by a new enum entry placeholder label,
     * by which the label view recognises a label it has to commit through this operation.
     */
    export const NEW_LABEL_KIND = "enum-entry-edit";

    /**
     * Parameters used to create an add-enum-entry operation.
     */
    export interface Options {
        /**
         * The identifier of the enum to update.
         */
        enumId: string;

        /**
         * The entry name to create.
         */
        entryName?: string;
    }

    /**
     * Create an {@link AddEnumEntryOperation}.
     *
     * @param options The operation payload.
     * @returns A new add-enum-entry operation.
     */
    export function create(options: Options): AddEnumEntryOperation {
        const value = options.entryName ?? "";
        return {
            kind: KIND,
            isOperation: true,
            enumId: options.enumId,
            entryName: value,
            parentElementId: options.enumId,
            labelText: value
        };
    }

    /**
     * Check whether an operation is an {@link AddEnumEntryOperation}.
     *
     * @param operation The operation to inspect.
     * @returns True if the operation is an add-enum-entry operation.
     */
    export function is(operation: Operation): operation is AddEnumEntryOperation {
        return operation.kind === KIND;
    }
}
