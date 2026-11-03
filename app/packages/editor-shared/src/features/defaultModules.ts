import { boundsModule } from "./bounds/featureModule.js";
import { changeBoundsToolModule } from "./change-bounds-tool/featureModule.js";
import { contextActionsModule } from "./context-actions/featureModule.js";
import { edgeEditToolModule } from "./edge-edit-tool/featureModule.js";
import { edgeRoutingModule } from "./edge-routing/featureModule.js";
import { elementFinderModule } from "./element-finder/featureModule.js";
import { editLabelModule } from "./edit-label/featureModule.js";
import { metadataModule } from "./metadata/featureModule.js";
import { moveModule } from "./move/featureModule.js";
import { pointerToolModule } from "./pointer-tool/featureModule.js";
import { reconnectEdgeModule } from "./reconnect-edge/featureModule.js";
import { revealSourceModule } from "./reveal-source/featureModule.js";
import { remoteUndoRedoModule } from "./undo-redo/featureModule.js";
import { viewportModule } from "./viewport/featureModule.js";
import { toolboxModule } from "./toolbox/featureModule.js";
import { gridModule } from "./grid/featureModule.js";
import { marqueeSelectionToolModule } from "./marquee-selection-tool/featureModule.js";
import { handToolModule } from "./hand-tool/featureModule.js";
import { selectModule } from "./select/featureModule.js";
import { createEdgeToolModule } from "./create-edge-tool/featureModule.js";
import { createNodeToolModule } from "./create-node-tool/featureModule.js";
import { iconRegistryModule } from "./icon-registry/featureModule.js";
import { decorationModule } from "./decoration/featureModule.js";
import { editorSettingsModule } from "./editor-settings/editorSettingsModule.js";
import { toolStateModule } from "./tool-state/featureModule.js";
import { copyPasteModule } from "./copy-paste/featureModule.js";
import { choiceDialogModule } from "./choice-dialog/featureModule.js";
import { sharedImport } from "../sharedImport.js";

const { standaloneCopyPasteModule, standaloneSelectModule } = sharedImport("@eclipse-glsp/client");

/**
 * Default modules for the editor shared features.
 * These modules are automatically included in editor configurations
 */
export const DEFAULT_MODULES = [
    boundsModule,
    changeBoundsToolModule,
    contextActionsModule,
    edgeEditToolModule,
    elementFinderModule,
    metadataModule,
    editLabelModule,
    edgeRoutingModule,
    moveModule,
    pointerToolModule,
    reconnectEdgeModule,
    revealSourceModule,
    remoteUndoRedoModule,
    viewportModule,
    editorSettingsModule,
    toolboxModule,
    gridModule,
    marqueeSelectionToolModule,
    handToolModule,
    selectModule,
    createEdgeToolModule,
    createNodeToolModule,
    iconRegistryModule,
    decorationModule,
    toolStateModule,
    copyPasteModule,
    choiceDialogModule,
    standaloneCopyPasteModule,
    standaloneSelectModule
];
