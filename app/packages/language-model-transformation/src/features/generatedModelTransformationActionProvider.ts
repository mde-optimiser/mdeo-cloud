import type { GetFileActionsParams, GetFileActionsResponse, ActionIconNode } from "@mdeo/language-common";
import { convertIcon, ActionDisplayLocation } from "@mdeo/language-common";
import type { ActionProvider } from "@mdeo/language-shared";
import { Save } from "lucide";

/**
 * Action provider for generated model transformation files (.mt_gen).
 */
export class GeneratedModelTransformationActionProvider implements ActionProvider {
    async getFileActions(params: GetFileActionsParams): Promise<GetFileActionsResponse> {
        if (params.languageId !== "model-transformation_gen") {
            return { actions: [] };
        }

        const saveIcon: ActionIconNode = convertIcon(Save);

        return {
            actions: [
                {
                    name: "Save as Model Transformation",
                    icon: saveIcon,
                    key: "save-as-model-transformation",
                    displayLocations: [ActionDisplayLocation.EDITOR_TITLE, ActionDisplayLocation.CONTEXT_MENU]
                }
            ]
        };
    }
}
