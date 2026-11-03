import type { VNode } from "snabbdom";
import type {
    Action,
    IActionDispatcher,
    GModelRoot,
    PaletteItem,
    SetContextActions,
    Args
} from "@eclipse-glsp/sprotty";
import type { Marker } from "@eclipse-glsp/protocol";
import type MiniSearch from "minisearch";
import { sharedImport } from "../../sharedImport.js";
import { ToolType, ToolboxGroupKey, type ToolDefinition, type ToolboxEditEntry } from "./toolboxTypes.js";
import { generateToolboxView } from "./views/toolboxView.js";
import { generateLayoutPanelView } from "./views/layoutPanelView.js";
import { enableTool } from "./tools.js";
import { ScrollViewState } from "./views/scrollView.js";
import { PreviewRenderer } from "./previewRenderer.js";
import { LayoutAction } from "./layoutAction.js";
import { CreateEdgeTool } from "../create-edge-tool/createEdgeTool.js";
import { EditorSettingsManager } from "../editor-settings/editorSettingsManager.js";
import { ToolStateManager } from "../tool-state/toolStateManager.js";
import type { EditorSettings } from "@mdeo/editor-common";
import type { LayoutAlgorithmKind } from "@mdeo/protocol-common";
import {
    LAYOUT_ALGORITHM_OPTIONS,
    readDefaultLayoutAlgorithm,
    writeDefaultLayoutAlgorithm,
    type LayoutAlgorithmOption
} from "./layoutAlgorithmPreference.js";

const { injectable, inject } = sharedImport("inversify");
const { html, TYPES } = sharedImport("@eclipse-glsp/sprotty");
import { HandTool } from "../hand-tool/handTool.js";

const { ToolPalette, EnableDefaultToolsAction, EnableToolsAction, MarqueeMouseTool, RequestContextActions } =
    sharedImport("@eclipse-glsp/client");
const { init, classModule, propsModule, styleModule, eventListenersModule, attributesModule } =
    sharedImport("snabbdom");
const { SetMarkersAction } = sharedImport("@eclipse-glsp/protocol");
const MiniSearchLib = sharedImport("minisearch");

const patcher = init([classModule, propsModule, styleModule, eventListenersModule, attributesModule]);

/**
 * Toolbox UI extension that provides tool selection and element palette functionality.
 * Extends the GLSP ToolPalette with Snabbdom-based rendering and enhanced search.
 */
@injectable()
export class Toolbox extends ToolPalette {
    static readonly TOOLBOX_ID = "toolbox";

    @inject(TYPES.IActionDispatcher)
    declare protected actionDispatcher: IActionDispatcher;

    @inject(PreviewRenderer)
    public previewRenderer!: PreviewRenderer;

    @inject(EditorSettingsManager)
    declare protected editorSettings: EditorSettingsManager;

    @inject(ToolStateManager)
    protected toolStateManager!: ToolStateManager;

    protected currentVNode?: VNode;
    protected searchIndex?: MiniSearch<ToolboxEditEntry>;
    protected toolboxEntries: ToolboxEditEntry[] = [];

    public detailsScrollState: ScrollViewState = new ScrollViewState();
    public errorScrollState: ScrollViewState = new ScrollViewState();

    public isOpen: boolean = true;
    public isBottomPanelOpen: boolean = true;
    public toolType: ToolType = ToolType.POINTER;
    public showPreviewFor?: string;
    public searchString: string = "";
    public markers: Marker[] = [];
    public selectedItemIndex: number = 0;
    public searchDebounceTimeout?: number;

    /**
     * Whether the layout tool is active, i.e. the layout panel (algorithm choice) is shown instead of the bottom panel.
     */
    public layoutPanelOpen: boolean = false;

    /**
     * Listener leaving the layout tool on Escape, registered while it is active.
     */
    protected layoutPanelKeydown?: (event: KeyboardEvent) => void;

    /**
     * Indicates whether the toolbox is in layout-only mode, which affects available tools and palette items.
     */
    get isLayoutable(): boolean {
        return this.editorContext.editMode === "layoutable";
    }

    /**
     * Returns the unique DOM ID used by the GLSP framework to locate this UI extension.
     *
     * @returns The toolbox element ID string.
     */
    override id(): string {
        return Toolbox.ID;
    }

    /**
     * Returns the CSS class name applied to the toolbox container element.
     *
     * @returns The container CSS class string.
     */
    override containerClass(): string {
        return "toolbox-wrapper";
    }

    /**
     * Initializes the toolbox contents with Snabbdom rendering.
     *
     * @param containerElement The container element to render into
     */
    protected override initializeContents(containerElement: HTMLElement): void {
        const toolbox = document.createElement("div");
        containerElement.appendChild(toolbox);
        containerElement.classList.add("mdeo-toolbox");
        this.currentVNode = this.patch(toolbox, html("div", null));
    }

    /**
     * Creates the header section. Overridden to prevent default DOM rendering.
     */
    protected override createHeader(): void {
        // Rendering is handled by Snabbdom
    }

    /**
     * Creates the body section. Overridden to prevent default DOM rendering.
     */
    protected override createBody(): void {
        // Rendering is handled by Snabbdom
    }

    /**
     * Adds the minimize button. Overridden to prevent default DOM rendering.
     */
    protected override addMinimizePaletteButton(): void {
        // Rendering is handled by Snabbdom
    }

    /**
     * No-op override — the toolbox remains available in all edit modes, including
     * read-only and layout-only modes.
     *
     * @param _newValue The new edit mode value (unused).
     * @param _oldValue The previous edit mode value (unused).
     */
    override editModeChanged(_newValue: string, _oldValue: string): void {
        // no action needed, toolbox available even in readonly mode
    }

    /**
     * Called before the initial model request.
     * Applies the persisted editor settings to the local state and dispatches them
     * to the language server so the server holds the current state from the start.
     */
    override async preRequestModel(): Promise<void> {
        const settings = this.editorSettings.getCurrentSettings();
        this.applySettings(settings);
        await this.editorSettings.syncToServer();
    }

    /**
     * Loads palette items from the server and shows the toolbox.
     * Activates the settings manager to listen for external (cross-tab) changes
     * and subscribes to setting updates so the UI reflects them immediately.
     */
    override async postRequestModel(): Promise<void> {
        await this.setPaletteItems();
        this.show(this.editorContext.modelRoot);

        this.editorSettings.activate();
        this.editorSettings.addListener((settings) => {
            this.applySettings(settings);
            this.update();
        });
    }

    /**
     * Patches the current VNode with a new one.
     *
     * @param oldVNode The old VNode or element
     * @param newVNode The new VNode
     * @returns The patched VNode
     */
    protected patch(oldVNode: VNode | Element, newVNode: VNode): VNode {
        return patcher(oldVNode, newVNode);
    }

    /**
     * Updates the toolbox by re-rendering with Snabbdom.
     */
    update(): void {
        if (this.currentVNode) {
            this.currentVNode = this.patch(this.currentVNode, this.generateMainContent());
        }
    }

    /**
     * Generates the main content VNode for the toolbox.
     * Can be overridden to customize the rendering.
     *
     * @returns The main content VNode
     */
    protected generateMainContent(): VNode {
        return generateToolboxView(this);
    }

    /**
     * Generates additional content shown right below the toolbar while the toolbox is open.
     * Unlike {@link generateDetailsExtension}, the content stays visible whatever tool is
     * active and in read-only mode. Override in subclasses to add such UI (e.g. a view switcher).
     *
     * @returns The VNode to render below the toolbar, undefined by default
     */
    generateToolbarExtension(): VNode | undefined {
        return undefined;
    }

    /**
     * Generates additional content to be inserted before the search input in the details panel.
     * Override in subclasses to add extension UI (e.g., edge type selectors).
     *
     * @returns Array of VNodes to render before the search container, empty by default
     */
    generateDetailsExtension(): VNode[] {
        return [];
    }

    /**
     * Called before showing the toolbox.
     *
     * @param containerElement The container element
     * @param root The model root
     */
    protected override onBeforeShow(containerElement: HTMLElement, root: Readonly<GModelRoot>): void {
        super.onBeforeShow(containerElement, root);
        this.buildToolboxEntries();
        this.update();
    }

    /**
     * Reloads the palette body, refreshing items if dynamic and not readonly.
     */
    protected override async reloadPaletteBody(): Promise<void> {
        if (!this.isLayoutable && this.dynamic) {
            await this.setPaletteItems();
            this.buildToolboxEntries();
            this.update();
        }
    }

    /**
     * Sets the palette items and marks the toolbox as dynamic.
     */
    protected override async setPaletteItems(): Promise<void> {
        const requestAction = RequestContextActions.create({
            contextId: ToolPalette.ID,
            editorContext: {
                selectedElementIds: [],
                args: this.generateRequestItemsArgs()
            }
        });
        const response = await this.actionDispatcher.request<SetContextActions>(requestAction);
        this.paletteItems = response.actions.map((action) => action as PaletteItem);
        this.dynamic = true;
    }

    /**
     * Generates arguments for the toolbox items request. Can be overridden to provide context-specific arguments.
     *
     * @returns Arguments for the toolbox items request, or undefined if no arguments are needed
     */
    protected generateRequestItemsArgs(): Args | undefined {
        return undefined;
    }

    /**
     * Changes the active tool back to pointer if it is not already, and updates the UI.
     * Called by the GLSP framework when {@link EnableDefaultToolsAction} is dispatched,
     * including after a node is created or the user cancels a creation gesture.
     */
    override changeActiveButton(): void {
        if (this.toolType !== ToolType.POINTER) {
            this.toolType = ToolType.POINTER;
            this.toolStateManager.setActiveTool(ToolType.POINTER);
            this.update();
        }
    }

    /**
     * Builds toolbox entries from palette items.
     */
    protected buildToolboxEntries(): void {
        this.toolboxEntries = [];
        this.searchIndex = undefined;

        if (!this.paletteItems) {
            return;
        }

        for (const item of this.paletteItems) {
            if (item.children) {
                const groupKey = new ToolboxGroupKey(item.label, item.sortString);
                for (const child of item.children) {
                    this.toolboxEntries.push(this.createToolboxEntry(child, groupKey));
                }
            } else {
                this.toolboxEntries.push(this.createToolboxEntry(item, new ToolboxGroupKey("", item.sortString)));
            }
        }
    }

    /**
     * Creates a toolbox entry from a palette item.
     *
     * @param item The palette item
     * @param group The group name
     * @returns The toolbox entry
     */
    protected createToolboxEntry(item: PaletteItem, group: ToolboxGroupKey): ToolboxEditEntry {
        return {
            id: item.id,
            name: item.label,
            group: group || "Other",
            keywords: [item.label.toLowerCase()],
            paletteItem: item
        };
    }

    /**
     * Gets the search index, building it if necessary.
     *
     * @returns The MiniSearch index
     */
    protected getSearchIndex(): MiniSearch<ToolboxEditEntry> {
        if (!this.searchIndex) {
            this.searchIndex = new MiniSearchLib.default<ToolboxEditEntry>({
                fields: ["name", "keywords"],
                storeFields: ["id", "name", "group", "keywords", "paletteItem"],
                idField: "id"
            });
            this.searchIndex.addAll(this.toolboxEntries);
        }
        return this.searchIndex;
    }

    /**
     * Gets filtered items based on the current search string.
     *
     * @returns Array of filtered toolbox entries
     */
    getFilteredItems(): ToolboxEditEntry[] {
        if (this.searchString.length === 0) {
            return this.toolboxEntries;
        }

        const searchIndex = this.getSearchIndex();
        const results = searchIndex.search(this.searchString, { prefix: true, fuzzy: 0.2 });
        const entryMap = new Map(this.toolboxEntries.map((e) => [e.id, e]));
        return results
            .map((result: { id: string }) => entryMap.get(result.id))
            .filter((entry): entry is ToolboxEditEntry => entry !== undefined);
    }

    /**
     * Checks if there are any toolbox items at all (regardless of filtering).
     *
     * @returns True if there are any toolbox items
     */
    hasToolboxItems(): boolean {
        return this.toolboxEntries.length > 0 && !this.isLayoutable;
    }

    /**
     * Toggles the toolbox open/closed state, persists, and notifies the language server.
     */
    toggleToolbox(): void {
        this.isOpen = !this.isOpen;
        this.editorSettings.saveAndSync(this.buildSettings());
        this.update();
    }

    /**
     * Toggles the bottom panel open/closed state, persists, and notifies the language server.
     */
    protected toggleBottomPanel(): void {
        this.isBottomPanelOpen = !this.isBottomPanelOpen;
        this.editorSettings.saveAndSync(this.buildSettings());
        this.update();
    }

    /**
     * Builds the current {@link EditorSettings} snapshot from the live state.
     *
     * @returns The settings reflecting the current toolbox state.
     */
    protected buildSettings(): EditorSettings {
        return {
            isOpen: this.isOpen,
            isBottomSidebarCollapsed: !this.isBottomPanelOpen
        };
    }

    /**
     * Applies the given settings to the toolbox state.
     *
     * @param settings The settings to apply.
     */
    protected applySettings(settings: EditorSettings): void {
        this.isOpen = settings.isOpen;
        this.isBottomPanelOpen = !settings.isBottomSidebarCollapsed;
    }

    /**
     * Triggers diagram layout with the default algorithm and activates the layout tool (layout panel).
     */
    protected layoutDiagram(): void {
        if (this.toolType !== ToolType.POINTER) {
            this.updateTool(ToolType.POINTER);
        }
        this.actionDispatcher.dispatch(LayoutAction.create(this.layoutAlgorithm));
        this.openLayoutPanel();
    }

    /**
     * The default layout algorithm of this diagram type (remembered in the browser).
     */
    get layoutAlgorithm(): LayoutAlgorithmKind {
        return readDefaultLayoutAlgorithm(this.diagramType());
    }

    /**
     * Returns the layout algorithms offered in the layout panel. Override to restrict them.
     *
     * @returns The algorithms
     */
    layoutAlgorithmOptions(): readonly LayoutAlgorithmOption[] {
        return LAYOUT_ALGORITHM_OPTIONS;
    }

    /**
     * Lays the diagram out with the given algorithm and makes it the default of this diagram type.
     *
     * @param algorithm The algorithm
     */
    selectLayoutAlgorithm(algorithm: LayoutAlgorithmKind): void {
        writeDefaultLayoutAlgorithm(this.diagramType(), algorithm);
        this.actionDispatcher.dispatch(LayoutAction.create(algorithm));
        this.update();
    }

    /**
     * Generates the layout panel, shown instead of the bottom panel while the layout tool is active.
     *
     * @returns The panel VNode, undefined while the layout tool is inactive or the bottom panel is hidden
     */
    generateLayoutPanel(): VNode | undefined {
        return this.layoutPanelOpen && this.isBottomPanelOpen ? generateLayoutPanelView(this) : undefined;
    }

    /**
     * Activates the layout tool: the layout panel replaces the bottom panel and the toolbar extension.
     * The bottom panel toggle shows / hides it like the regular bottom panel; Escape or another tool leave it again.
     */
    openLayoutPanel(): void {
        if (!this.layoutPanelOpen) {
            this.layoutPanelOpen = true;
            this.layoutPanelKeydown = (event: KeyboardEvent) => {
                if (event.key === "Escape") {
                    this.closeLayoutPanel();
                }
            };
            document.addEventListener("keydown", this.layoutPanelKeydown, true);
        }
        this.update();
    }

    /**
     * Leaves the layout tool.
     */
    closeLayoutPanel(): void {
        if (this.layoutPanelKeydown != undefined) {
            document.removeEventListener("keydown", this.layoutPanelKeydown, true);
            this.layoutPanelKeydown = undefined;
        }
        if (this.layoutPanelOpen) {
            this.layoutPanelOpen = false;
            this.update();
        }
    }

    /**
     * Returns the diagram type of the editor, undefined if it is not known yet.
     *
     * @returns The diagram type
     */
    protected diagramType(): string | undefined {
        try {
            return this.editorContext.diagramType;
        } catch {
            return undefined;
        }
    }

    /**
     * Updates the current tool.
     *
     * @param tool The tool type to set
     */
    updateTool(tool: ToolType): void {
        this.closeLayoutPanel();
        if (this.toolType === tool && tool !== ToolType.POINTER) {
            return;
        }

        this.toolType = tool;
        this.toolStateManager.setActiveTool(tool);

        const action = this.getToolAction(tool);
        if (action) {
            this.actionDispatcher.dispatch(action);
        }

        this.update();
    }

    /**
     * Gets the action to dispatch for a tool.
     *
     * @param tool The tool type
     * @returns The action to dispatch
     */
    protected getToolAction(tool: ToolType): Action | undefined {
        switch (tool) {
            case ToolType.POINTER:
                return EnableDefaultToolsAction.create();
            case ToolType.HAND:
                return EnableToolsAction.create([HandTool.ID]);
            case ToolType.MARQUEE:
                return EnableToolsAction.create([MarqueeMouseTool.ID]);
            case ToolType.CREATE_EDGE:
                return EnableToolsAction.create([CreateEdgeTool.ID]);
            default:
                return undefined;
        }
    }

    /**
     * Handles tool button clicks.
     *
     * @param tool The tool definition that was clicked
     */
    onToolClick(tool: ToolDefinition): void {
        if (tool.id === ToolType.BOTTOM_PANEL_TOGGLE) {
            this.toggleBottomPanel();
        } else if (tool.id === ToolType.LAYOUT) {
            this.layoutDiagram();
        } else {
            this.closeLayoutPanel();
            enableTool(this, tool.id);
        }
    }

    /**
     * Handles palette item clicks.
     *
     * @param item The toolbox entry that was clicked
     */
    onPaletteItemClick(item: ToolboxEditEntry): void {
        if (this.editorContext.isReadonly) {
            return;
        }

        const isNodeCreation = item.paletteItem.actions.some((a) => a.kind === "triggerNodeCreation");
        if (isNodeCreation) {
            this.toolType = ToolType.CREATE_NODE;
            this.toolStateManager.setActiveTool(ToolType.CREATE_NODE);
            this.update();
        }

        this.actionDispatcher.dispatchAll(item.paletteItem.actions);
    }

    /**
     * Routes incoming actions to the appropriate handler.
     * Handles {@link SetMarkersAction} to update the error panel;
     * all other actions are delegated to the parent implementation.
     *
     * {@link UpdateEditorSettingsAction} from the server is handled by
     * {@link EditorSettingsActionHandler} which notifies this toolbox via
     * the {@link EditorSettingsManager} listener registered in {@link postRequestModel}.
     *
     * @param action The action dispatched by the GLSP framework.
     */
    override handle(action: Action): void {
        if (SetMarkersAction.is(action)) {
            this.handleSetMarkers(action);
            return;
        }
        super.handle(action);
    }

    /**
     * Handles incoming marker updates by replacing the current markers and refreshing the UI.
     *
     * @param action The SetMarkersAction containing the new markers
     */
    protected handleSetMarkers(action: { markers: Marker[] }): void {
        this.markers = action.markers;
        this.update();
    }
}
