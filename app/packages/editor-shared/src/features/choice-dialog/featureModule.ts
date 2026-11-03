import { sharedImport } from "../../sharedImport.js";
import { ChoiceDialog } from "./choiceDialog.js";

const { FeatureModule } = sharedImport("@eclipse-glsp/client");

/**
 * Feature module providing the {@link ChoiceDialog}, which editors use to ask the user before
 * an operation that can be carried out in more than one way.
 */
export const choiceDialogModule = new FeatureModule(
    (bind) => {
        bind(ChoiceDialog).toSelf().inSingletonScope();
    },
    { featureId: Symbol("choiceDialog") }
);
