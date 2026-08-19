import { sharedImport } from "../../sharedImport.js";

const { GModelElement, GModelElementBuilder } = sharedImport("@eclipse-glsp/server");

/**
 * A label element.
 */
export class GLabel extends GModelElement {
    /**
     * The text content of the label
     */
    text!: string;

    /**
     * Indicates if the label is read-only
     */
    readonly?: boolean;

    /**
     * When {@code true}, the client view will automatically enter edit mode and use
     * {@link newLabelOperationKind} to create the commit operation instead of the
     * standard {@code ApplyLabelEditOperation}.
     */
    isNewLabel?: boolean;

    /**
     * Automatically set to true if isNewLabel is true
     */
    editMode?: boolean;

    /**
     * Identifies the operation factory registered in the client's
     * {@code OperationFactoryRegistry} that should be used when the user commits
     * a new label.  Only meaningful when {@link isNewLabel} is {@code true}.
     */
    newLabelOperationKind?: string;

    /**
     * The ID of the parent GModel element that contextualises this label.
     * Forwarded to the operation factory so the server handler can locate the
     * owning element when committing the new label text.
     * Only meaningful when {@link isNewLabel} is {@code true}.
     */
    parentElementId?: string;

    /**
     * Everything the commit operation needs beyond {@link parentElementId}.
     *
     * A label whose target is not a GModel element of its own cannot be described by a
     * parent ID alone — the clause of an application condition block, for instance, is
     * placed on the match node but belongs to one of its blocks. Such a label carries what
     * distinguishes it here, instead of encoding it into {@link newLabelOperationKind} or
     * into the label ID, which the receiving view would then have to take apart again.
     *
     * Only meaningful when {@link isNewLabel} is {@code true}.
     */
    newLabelOperationArgs?: Record<string, string | number | boolean>;
}

/**
 * Builder for GLabel instances.
 * Provides fluent API for constructing labels.
 */
export class GLabelBuilder<T extends GLabel = GLabel> extends GModelElementBuilder<T> {
    /**
     * Sets the text content of the label.
     *
     * @param text The text to display
     * @returns This builder for chaining
     */
    text(text: string): this {
        this.proxy.text = text;
        return this;
    }

    /**
     * Sets whether the label is read-only.
     *
     * @param readonly True if the label should be read-only, false otherwise
     * @returns This builder for chaining
     */
    readonly(readonly: boolean): this {
        this.proxy.readonly = readonly;
        return this;
    }

    /**
     * Marks the label as newly created so that the client view enters edit mode
     * automatically and uses {@code newLabelOperationKind} for committing.
     *
     * @param isNewLabel Whether the label is newly created
     * @returns This builder for chaining
     */
    isNewLabel(isNewLabel: boolean): this {
        this.proxy.isNewLabel = isNewLabel;
        if (isNewLabel) {
            this.proxy.editMode = true;
        }
        return this;
    }

    /**
     * Sets the operation kind that the client's {@code OperationFactoryRegistry}
     * should use when the user commits this new label.
     *
     * @param kind The operation kind string
     * @returns This builder for chaining
     */
    newLabelOperationKind(kind: string): this {
        this.proxy.newLabelOperationKind = kind;
        return this;
    }

    /**
     * Sets the parent element ID forwarded to the commit operation factory.
     *
     * @param parentElementId The parent GModel element ID
     * @returns This builder for chaining
     */
    newLabelParentElementId(parentElementId: string): this {
        this.proxy.parentElementId = parentElementId;
        return this;
    }

    /**
     * Sets the arguments forwarded to the commit operation factory alongside the
     * parent element ID.
     *
     * @param args The arguments the operation needs; see {@link GLabel.newLabelOperationArgs}
     * @returns This builder for chaining
     */
    newLabelOperationArgs(args: Record<string, string | number | boolean>): this {
        this.proxy.newLabelOperationArgs = args;
        return this;
    }
}
