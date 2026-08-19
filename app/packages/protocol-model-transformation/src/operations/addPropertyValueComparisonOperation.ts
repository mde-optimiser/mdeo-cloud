import type { Operation } from "@eclipse-glsp/protocol";
import type { NewLabelOperation } from "@mdeo/protocol-common";

/**
 * Operation to add a property value/comparison entry to a pattern instance.
 */
export interface AddPropertyValueComparisonOperation extends NewLabelOperation {
    /**
     * Operation kind discriminator.
     */
    kind: "addPropertyValueComparison";

    /**
     * Identifier of the pattern instance node to update.
     */
    instanceId: string;
}

/**
 * Namespace helpers for add-property-value-comparison operations.
 */
export namespace AddPropertyValueComparisonOperation {
    /**
     * Operation kind constant.
     */
    export const KIND = "addPropertyValueComparison";

    /**
     * Identifier carried in `newLabelOperationKind` by a new property value or comparison placeholder label,
     * by which the label view recognises a label it has to commit through this operation.
     */
    export const NEW_LABEL_KIND = "property-value-comparison-edit";

    /**
     * Payload for creating an add-property-value-comparison operation.
     */
    export interface Options {
        /**
         * Identifier of the pattern instance node to update.
         */
        instanceId: string;

        /**
         * The full edited text (e.g. {@code propName = expr} or {@code propName == expr})
         * to insert into the model.  The server reads this verbatim.
         */
        labelText?: string;
    }

    /**
     * Creates an add-property-value-comparison operation.
     *
     * @param options Operation payload
     * @returns Operation instance
     */
    export function create(options: Options): AddPropertyValueComparisonOperation {
        return {
            kind: KIND,
            isOperation: true,
            instanceId: options.instanceId,
            parentElementId: options.instanceId,
            labelText: options.labelText ?? ""
        };
    }

    /**
     * Checks whether an operation is an add-property-value-comparison operation.
     *
     * @param operation Operation to check
     * @returns True when operation kind matches
     */
    export function is(operation: Operation): operation is AddPropertyValueComparisonOperation {
        return operation.kind === KIND;
    }
}
