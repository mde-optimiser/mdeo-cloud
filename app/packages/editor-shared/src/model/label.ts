import type { EditLabelValidationResult } from "@eclipse-glsp/sprotty";
import { sharedImport } from "../sharedImport.js";

const { GChildElement, editLabelFeature } = sharedImport("@eclipse-glsp/sprotty");

/**
 * Client-side model for HTML text labels.
 * Contains text content that can be rendered as HTML.
 */
export class GLabel extends GChildElement {
    static readonly DEFAULT_FEATURES = [editLabelFeature];

    /**
     * The text content to be displayed
     */
    text!: string;

    /**
     * Indicates if the label is read-only
     */
    readonly?: boolean;

    /**
     * Indicates if the label is currently in edit mode
     */
    editMode: boolean = false;

    /**
     * Indicates if the label should be rendered as multiline (textarea instead of input)
     */
    isMultiLine?: boolean;

    /**
     * Temporarily stores the edited text before applying
     */
    tempText?: string;

    /**
     * Stores the validation result for the current edit
     */
    validationResult?: EditLabelValidationResult;

    /**
     * Indicates if this label was just created and requires special handling.
     * When true, the label view will dispatch a custom operation instead of ApplyLabelEditOperation
     * and automatically enter edit mode for immediate user input.
     */
    isNewLabel?: boolean;

    /**
     * Specifies which operation factory to use when creating a new label.
     * Maps to the operation kind (e.g., "addPropertyLabel", "addEnumEntryLabel").
     * Only used when isNewLabel is true.
     */
    newLabelOperationKind?: string;

    /**
     * The parent element ID that provides context for new label operations.
     * For example, when creating a property label, this would be the containing class ID.
     * Only used when isNewLabel is true.
     */
    parentElementId?: string;

    /**
     * Everything the commit operation needs beyond the parent element ID, for a label whose
     * target is not a GModel element of its own — the clause of an application condition
     * block, for instance, sits on the match node but belongs to one of its blocks.
     * Only used when isNewLabel is true.
     */
    newLabelOperationArgs?: Record<string, string | number | boolean>;

    /**
     * The IDs of the root-level GModel elements that were inserted by the corresponding
     * {@link InsertNewLabelAction} (set by {@code InsertNewLabelCommand} after insertion).
     * Used by the label view when building a {@link RemoveNewLabelAction}.
     * Only populated when isNewLabel is true.
     */
    insertedElementIds?: string[];

    /**
     * The ID of the GModel element that directly contains the inserted template elements
     * (set by {@code InsertNewLabelCommand} after insertion).
     * Used by the label view when building a {@link RemoveNewLabelAction}.
     * Only populated when isNewLabel is true.
     */
    containerElementId?: string;
}
