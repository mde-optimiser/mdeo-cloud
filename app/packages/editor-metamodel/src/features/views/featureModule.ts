import type { Action, ICommand } from "@eclipse-glsp/sprotty";
import { sharedImport } from "@mdeo/editor-shared";
import { ConfirmViewDeletionAction } from "@mdeo/protocol-metamodel";
import { MetamodelViewChangeCommand, MetamodelViewTracker } from "./viewChangeViewportCommand.js";
import { MetamodelDelKeyDeleteTool } from "./metamodelDeleteTool.js";
import { ConfirmViewDeletionActionHandler } from "./confirmViewDeletionActionHandler.js";

const { FeatureModule, configureActionHandler, DelKeyDeleteTool } = sharedImport("@eclipse-glsp/client");
const { SetModelAction, UpdateModelAction, TYPES } = sharedImport("@eclipse-glsp/sprotty");
const { Container } = sharedImport("inversify");

/**
 * Feature module for the views of a metamodel:
 * - adjusts the viewport when the editor switches to another view,
 * - asks whether a class or enum deleted inside a view is removed from the view or deleted.
 */
export const metamodelViewsModule = new FeatureModule(
    (bind, _unbind, isBound, rebind) => {
        bind(MetamodelViewTracker).toSelf().inSingletonScope();
        bind(MetamodelViewChangeCommand).toSelf();
        for (const actionType of [SetModelAction.KIND, UpdateModelAction.KIND]) {
            bind(TYPES.CommandRegistration).toDynamicValue((ctx) => ({
                kind: actionType,
                factory: (action: Action) => {
                    const childContainer = new Container();
                    childContainer.parent = ctx.container;
                    childContainer.bind(TYPES.Action).toConstantValue(action);
                    return childContainer.get<ICommand>(MetamodelViewChangeCommand);
                }
            }));
        }

        bind(ConfirmViewDeletionActionHandler).toSelf().inSingletonScope();
        configureActionHandler({ bind, isBound }, ConfirmViewDeletionAction.KIND, ConfirmViewDeletionActionHandler);

        rebind(DelKeyDeleteTool).to(MetamodelDelKeyDeleteTool).inSingletonScope();
    },
    { featureId: Symbol("metamodelViews") }
);
