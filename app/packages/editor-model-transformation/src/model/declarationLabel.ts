import { GLabel, sharedImport } from "@mdeo/editor-shared";

const { selectFeature, deletableFeature, editLabelFeature } = sharedImport("@eclipse-glsp/sprotty");

/**
 * Client-side model for a declaration row of the start node (an input) or of a successful end
 * node (an output): the declaration as written. A row can be selected and deleted (which
 * deletes its line) and edited (which replaces its line).
 */
export class GDeclarationLabel extends GLabel {
    static override readonly DEFAULT_FEATURES = [editLabelFeature, selectFeature, deletableFeature];

    /**
     * Indicates whether the row is selected.
     */
    selected: boolean = false;
}
