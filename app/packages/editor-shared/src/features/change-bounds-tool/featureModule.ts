import { sharedImport } from "../../sharedImport.js";
import { ChangeBoundsManager } from "./changeBoundsManager.js";
import { ChangeBoundsTool } from "./changeBoundsTool.js";
import {
    NoopHideChangeBoundsToolResizeFeedbackCommand,
    NoopShowChangeBoundsToolResizeFeedbackCommand
} from "./changeBoundsToolFeedback.js";

const {
    FeatureModule,
    ChangeBoundsTool: GLSPChangeBoundsTool,
    ChangeBoundsManager: GLSPChangeBoundsManager,
    ShowChangeBoundsToolResizeFeedbackCommand,
    HideChangeBoundsToolResizeFeedbackCommand
} = sharedImport("@eclipse-glsp/client");

/**
 * Feature module for the change bounds tool.
 * Provides a custom change bounds tool implementation that supports:
 * - Custom SVG-based resize handles with data attributes
 * - Separation of move and resize operations
 * - Elements that know a minimum size of their own
 */
export const changeBoundsToolModule = new FeatureModule(
    (bind, unbind, isBound, rebind) => {
        bind(ChangeBoundsTool).toSelf().inSingletonScope();
        rebind(GLSPChangeBoundsTool).toService(ChangeBoundsTool);

        bind(ChangeBoundsManager).toSelf().inSingletonScope();
        rebind(GLSPChangeBoundsManager).toService(ChangeBoundsManager);

        bind(NoopShowChangeBoundsToolResizeFeedbackCommand).toSelf();
        bind(NoopHideChangeBoundsToolResizeFeedbackCommand).toSelf();
        rebind(ShowChangeBoundsToolResizeFeedbackCommand).toService(NoopShowChangeBoundsToolResizeFeedbackCommand);
        rebind(HideChangeBoundsToolResizeFeedbackCommand).toService(NoopHideChangeBoundsToolResizeFeedbackCommand);
    },
    { featureId: Symbol("change-bounds-tool") }
);
