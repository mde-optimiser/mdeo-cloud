import { computed, nextTick, ref, shallowRef, toRaw, watch, type Reactive } from "vue";
import type { EditorTab } from "./tab/editorTab";
import type { BackendApi } from "./api/backendApi";
import type { MonacoApi } from "@/lib/monacoPlugin";
import type { Project } from "./project/project";
import type { Folder } from "./filesystem/file";
import { useFileTree } from "./filesystem/useFileTree";
import { BackendFileSystemProvider } from "./filesystem/fileSystemProvider";
import { registerFileSystemOverlay } from "@codingame/monaco-vscode-files-service-override";
import { BrowserMessageReader, BrowserMessageWriter } from "vscode-languageserver-protocol/browser";
import { MonacoLanguageClient } from "monaco-languageclient";
import { CloseAction, ErrorAction, State, type ResponseMessage } from "vscode-languageclient";
import { Uri } from "vscode";
import { MonacoGLSPClient } from "@/data/glspClient";
import { reinitializeWorkspace } from "@codingame/monaco-vscode-configuration-service-override";
import {
    GetPluginsRequest,
    ServerReadyNotification,
    ReadFileRequest,
    StatRequest,
    ReadDirectoryRequest,
    ReadMetadataRequest,
    WriteMetadataRequest,
    TriggerActionNotification,
    RevealSourceNotification,
    type ReadFileParams,
    type StatParams,
    type StatResponse,
    type ReadDirectoryParams,
    type FileSystemNode,
    type ReadMetadataParams,
    type WriteMetadataParams
} from "@/server/protocol";
import type { ResolvedWorkbenchLanguagePlugin, WorkbenchPlugin } from "./plugin/plugin";
import type { LanguageContributionPlugin } from "@mdeo/plugin";
import type { ServerPlugin } from "./plugin/serverPlugin";
import { resolvePlugin } from "./plugin/resolvePlugin";
import type { ActionStartParams } from "@mdeo/language-common";
import { parseUri, FileCategory } from "@mdeo/language-common";
import type { Execution, ExecutionFileEntry } from "./execution/execution";
import { buildExecutionFileTree } from "./execution/execution";
import type { ApiResult, ExecutionError } from "./api/apiResult";
import { showApiError } from "@/lib/notifications";
import { useDiagnostics, type DiagnosticStore } from "./diagnostics/useDiagnostics";

/**
 * Represents an execution with its loaded file tree data.
 */
export interface ExecutionWithLoadedTree {
    /**
     * The unique identifier of the execution
     */
    id: string;
    /**
     * The execution metadata
     */
    execution: Execution;
    /**
     * The loaded file tree for the execution as a tree structure (undefined if not loaded yet)
     */
    fileTree: Folder | undefined;
    /**
     * Whether the file tree is currently being loaded
     */
    isLoadingTree: boolean;
}

/**
 * Manager for the overall state of the workbench
 */
export class WorkbenchState {
    /**
     * The currently active sidebar
     */
    readonly activeSidebar = ref<SidebarType>("projects");

    /**
     * Whether the sidebar is collapsed
     */
    readonly sidebarCollapsed = ref(false);

    /**
     * The text the search panel searches for
     */
    readonly searchText = ref("");

    /**
     * Counter that is increased every time the search panel is revealed,
     * used by the panel to focus its input
     */
    readonly searchRevealCounter = ref(0);

    /**
     * Internal ref for the currently loaded project
     */
    private readonly _project = ref<Project>();

    /**
     * The currently open tabs in the editor
     */
    readonly tabs = ref<EditorTab[]>([]);

    /**
     * The currently active tab in the editor
     */
    readonly activeTab = ref<EditorTab>();

    /**
     * Plugins loaded in the workbench. The map is only ever replaced as a whole, and its plugins
     * hold editor objects that must not be wrapped in reactive proxies, so the ref is shallow.
     */
    readonly plugins = shallowRef<Map<string, WorkbenchPlugin>>(new Map());

    /**
     * The list of executions for the current project.
     * Map from execution ID to execution with loaded tree data.
     */
    readonly executions = ref<Map<string, ExecutionWithLoadedTree>>(new Map());

    /**
     * The current language client if existing
     * should be disposed before a new one is created
     */
    readonly languageClient = shallowRef<MonacoLanguageClient>();

    /**
     * The central GLSP client shared across all graphical editors.
     * Created when the language client is created.
     */
    readonly glspClient = shallowRef<MonacoGLSPClient>();

    /**
     * Counter for language client restarts
     * Can be used as vue key to force re-creation of components
     */
    readonly clientVersionCounter = ref(0);

    /**
     * The pending action to trigger an action dialog flow.
     * When set, the action dialog will be displayed.
     * Only one action can be active at a time; setting a new action discards the current one.
     */
    readonly pendingAction = shallowRef<ActionStartParams>();

    /**
     * Reactive diagnostic store tracking validation errors published by the language server.
     */
    readonly diagnosticStore: DiagnosticStore = useDiagnostics();

    /**
     * File type plugins provided by any plugin
     */
    readonly languagePlugins = computed<ResolvedWorkbenchLanguagePlugin[]>(() => {
        const plugins = [...this.plugins.value.values()];
        const contributionPluginsByLanguage = new Map<string, LanguageContributionPlugin[]>();
        for (const plugin of plugins) {
            for (const contributionPlugin of plugin.contributionPlugins) {
                if (!contributionPluginsByLanguage.has(contributionPlugin.languageId)) {
                    contributionPluginsByLanguage.set(contributionPlugin.languageId, []);
                }
                contributionPluginsByLanguage.get(contributionPlugin.languageId)!.push(toRaw(contributionPlugin));
            }
        }

        return plugins
            .flatMap((plugin) => plugin.languagePlugins.map((langPlugin) => ({ ...langPlugin })))
            .map((langPlugin) => ({
                ...langPlugin,
                contributionPlugins: contributionPluginsByLanguage.get(langPlugin.id) ?? []
            }));
    });

    /**
     * Map of language plugins by their file extension
     */
    readonly languagePluginByExtension = computed<Map<string, ResolvedWorkbenchLanguagePlugin>>(() => {
        const map = new Map<string, ResolvedWorkbenchLanguagePlugin>();
        for (const langPlugin of this.languagePlugins.value) {
            if (!langPlugin.extension) {
                continue;
            }
            if (map.has(langPlugin.extension)) {
                throw new Error(`Multiple language plugins registered for extension ${langPlugin.extension}`);
            }
            map.set(langPlugin.extension, langPlugin);
        }
        return map;
    });

    /**
     * Server plugins provided by any plugin
     */
    readonly serverPlugins = computed<ServerPlugin[]>(() =>
        this.languagePlugins.value.map((plugin) => ({
            id: plugin.id,
            extension: plugin.extension,
            contributionPlugins: plugin.contributionPlugins.flatMap(
                (contribution) => contribution.serverContributionPlugins
            ),
            ...plugin.serverPlugin
        }))
    );

    /**
     * The root folder of the file tree
     */
    readonly fileTree: Reactive<Folder>;

    /**
     * The project currently loaded in the workbench (if any)
     */
    readonly project = computed<Project | undefined>({
        get: () => this._project.value,
        set: (newProject) => {
            if (this._project.value?.id !== newProject?.id) {
                this._project.value = newProject;
                this.tabs.value = [];
                this.activeTab.value = undefined;
                this.executions.value = new Map();

                const newPath = `/${newProject?.id ?? ""}`;
                if (window.location.pathname !== newPath) {
                    window.history.pushState(null, "", newPath);
                }

                if (newProject != undefined) {
                    reinitializeWorkspace({
                        id: newProject.id,
                        uri: Uri.parse(`file:///${newProject.id}/files`)
                    });
                    this.backendApi.files.precache(newProject);
                }
            }
        }
    });

    /**
     * The current language server worker
     */
    private languageServerWorker: Worker | undefined;

    /**
     * Set of languages already registered with monaco
     */
    private registeredLanguages = new Set<string>();

    /**
     * Creates a new workbench state manager
     *
     * @param monacoApi The Monaco API instance
     * @param backendApi The backend API instance
     */
    constructor(
        readonly monacoApi: MonacoApi,
        readonly backendApi: BackendApi
    ) {
        const fileSystemProvider = new BackendFileSystemProvider(backendApi);
        registerFileSystemOverlay(1, fileSystemProvider);
        this.fileTree = useFileTree(monacoApi, this);
        this.initializeWebSocket();
        this.initiLsp();
    }

    /**
     * Opens the search panel and focuses its input.
     *
     * @param searchText the text to search for, the current search text is kept if it is not provided
     */
    revealSearch(searchText?: string): void {
        if (searchText != undefined && searchText.length > 0) {
            this.searchText.value = searchText;
        }
        this.activeSidebar.value = "search";
        this.sidebarCollapsed.value = false;
        this.searchRevealCounter.value++;
    }

    /**
     * Initializes the WebSocket connection and sets up the execution state change handler.
     * Subscribes to project changes to update the WebSocket subscription.
     */
    private initializeWebSocket(): void {
        this.backendApi.websocket.onExecutionStateChange((execution) => {
            this.handleExecutionStateChange(execution);
        });

        watch(
            () => this.project.value,
            (newProject) => {
                if (newProject) {
                    this.backendApi.websocket.subscribeToProject(newProject.id);
                } else {
                    this.backendApi.websocket.disconnect();
                }
            },
            { immediate: true }
        );
    }

    /**
     * Handles an execution state change notification from the WebSocket.
     * Updates the local execution state and triggers any necessary UI updates.
     * If an execution completes within 5 seconds, automatically opens the summary in a temporary tab.
     *
     * @param execution The updated execution data
     */
    private handleExecutionStateChange(execution: Execution): void {
        const existingData = this.executions.value.get(execution.id);

        this.executions.value.set(execution.id, {
            id: execution.id,
            execution,
            fileTree: existingData?.fileTree,
            isLoadingTree: existingData?.isLoadingTree ?? false
        });

        if (
            (execution.state === "completed" || execution.state === "failed") &&
            execution.startedAt != undefined &&
            execution.finishedAt != undefined &&
            this.project.value != null
        ) {
            const startTime = new Date(execution.startedAt).getTime();
            const completionTime = new Date(execution.finishedAt).getTime();
            const duration = completionTime - startTime;

            if (duration <= 5000) {
                nextTick(async () => {
                    await this.openExecutionSummary(execution.id, true);
                });
            }
        }
    }

    /**
     * Opens the execution summary in the editor.
     *
     * @param executionId The ID of the execution
     * @param temporary Whether to open as a temporary tab
     */
    private async openExecutionSummary(executionId: string, temporary: boolean = false): Promise<void> {
        if (this.project.value == null) {
            return;
        }

        const uri = Uri.file(`/${this.project.value.id}/executions/${executionId}/report.md`);

        await this.monacoApi.editorService.openEditor({
            resource: uri,
            options: {
                preserveFocus: false
            }
        });

        if (temporary && this.activeTab.value != null) {
            this.activeTab.value.temporary = true;
        }
    }

    /**
     * Refreshes the list of executions for the current project.
     * Only updates non-terminal executions to preserve loaded data for completed/failed/cancelled executions.
     *
     * @returns A promise that resolves when the refresh is complete
     */
    async refreshExecutions(): Promise<void> {
        if (this.project.value == undefined) {
            this.executions.value = new Map();
            return;
        }

        const result = await this.backendApi.executions.list(this.project.value.id);
        if (!result.success) {
            showApiError("refresh executions", result.error.message);
            return;
        }

        for (const execution of result.value) {
            const existing = this.executions.value.get(execution.id);
            if (existing != undefined) {
                existing.execution = execution;
            } else {
                this.executions.value.set(execution.id, {
                    id: execution.id,
                    execution,
                    fileTree: undefined,
                    isLoadingTree: false
                });
            }
        }
    }

    /**
     * Loads the file tree for a specific execution.
     *
     * The tree is fetched on its own so it can be shown as soon as it arrives; the files
     * behind it are pulled in one bulk request in the background, which is what makes opening
     * any of them afterwards free.
     *
     * @param executionId The ID of the execution to load the file tree for
     * @returns A promise that resolves when the file tree is loaded
     */
    async loadExecutionFileTree(executionId: string): Promise<void> {
        await this.withExecutionTree(executionId, (projectId) =>
            this.backendApi.executions.loadResults(projectId, executionId)
        );
    }

    /**
     * Loads an execution's file tree *and* the content of every file in it.
     *
     * For an execution the user never expanded, this is a single round trip — the bulk load
     * answers with the tree as well — where fetching the tree first and the files second
     * would traverse the results twice over three hops.
     *
     * @param executionId The ID of the execution to load
     * @returns A promise that resolves when every result file is available
     */
    async loadAllExecutionResults(executionId: string): Promise<void> {
        await this.withExecutionTree(executionId, (projectId) =>
            this.backendApi.executions.loadAllResults(projectId, executionId)
        );
    }

    /**
     * Runs a result load against the current project and installs the resulting tree.
     *
     * @param executionId The ID of the execution being loaded
     * @param load Performs the load and returns the result tree entries
     */
    private async withExecutionTree(
        executionId: string,
        load: (projectId: string) => Promise<ApiResult<ExecutionFileEntry[], ExecutionError>>
    ): Promise<void> {
        const project = this.project.value;
        if (project == undefined) {
            return;
        }

        const executionData = this.executions.value.get(executionId);
        if (!executionData) {
            return;
        }

        // Only claim to be loading the tree when there is no tree yet. A load that already has
        // one — downloading the results of an execution the user expanded a moment ago — would
        // otherwise replace the rendered file list with a spinner and look like it was fetching
        // everything a second time.
        const showsProgress = executionData.fileTree == undefined;
        if (showsProgress) {
            executionData.isLoadingTree = true;
        }
        try {
            const result = await load(project.id);
            if (result.success) {
                executionData.fileTree = buildExecutionFileTree(result.value, executionId, project.id);
            } else {
                showApiError("load execution file tree", result.error.message);
            }
        } finally {
            if (showsProgress) {
                executionData.isLoadingTree = false;
            }
        }
    }

    /**
     * Adds a new execution to the executions list.
     * If the sidebar is open (not collapsed), automatically switch to the executions tab.
     * Records the start time for auto-opening quick executions.
     *
     * @param execution The execution to add
     */
    addExecution(execution: Execution): void {
        this.executions.value.set(execution.id, {
            id: execution.id,
            execution,
            fileTree: undefined,
            isLoadingTree: false
        });
        if (!this.sidebarCollapsed.value) {
            this.activeSidebar.value = "executions";
        }
    }

    /**
     * Reloads all plugins from the backend
     */
    private async reloadPlugins() {
        if (this.project.value == undefined) {
            this.plugins.value = new Map();
            return;
        }
        const newPlugins: Map<string, WorkbenchPlugin> = new Map();
        const pluginsResult = await this.backendApi.plugins.getForProject(this.project.value.id);
        if (!pluginsResult.success) {
            throw new Error("Failed to load plugins");
        }
        for (const plugin of pluginsResult.value) {
            newPlugins.set(plugin.id, await resolvePlugin(plugin));
        }
        this.plugins.value = newPlugins;
    }

    /**
     * Initializes the languge server and client
     * and sets up watching for project and language changes
     */
    private initiLsp() {
        watch(
            this.languagePlugins,
            (plugins) => {
                for (const plugin of plugins) {
                    if (!this.registeredLanguages.has(plugin.id)) {
                        this.monacoApi.monaco.languages.register({ id: plugin.id });
                        this.registeredLanguages.add(plugin.id);
                    }
                    if (plugin.textualEditorPlugin != undefined) {
                        this.monacoApi.monaco.languages.setLanguageConfiguration(
                            plugin.id,
                            plugin.textualEditorPlugin.languageConfiguration
                        );
                        this.monacoApi.monaco.languages.setMonarchTokensProvider(
                            plugin.id,
                            this.generateMonarchTokensProvider(plugin)
                        );
                    }
                }
            },
            { immediate: true }
        );

        watch(
            [this.project, this.serverPlugins],
            async ([newProject, newServerPlugins]) => {
                await this.recreateLanguageServer(newProject, newServerPlugins);
            },
            { immediate: true }
        );

        watch(this.project, () => this.reloadPlugins(), { immediate: true });
    }

    /**
     * Generates monarch tokens provider with merged keywords from contribution plugins.
     * Requires the plugin to have a textualEditorPlugin defined.
     *
     * @param plugin The resolved language plugin with textualEditorPlugin
     * @returns The monarch tokens provider with merged keywords
     */
    private generateMonarchTokensProvider(plugin: ResolvedWorkbenchLanguagePlugin) {
        const textualPlugin = plugin.textualEditorPlugin!;
        return {
            ...textualPlugin.monarchTokensProvider,
            keywords: [
                ...textualPlugin.monarchTokensProvider.keywords,
                ...plugin.contributionPlugins.flatMap(
                    (contributionPlugin) => contributionPlugin.additionalKeywords ?? []
                )
            ]
        };
    }

    /**
     * Recreates the language server and client when configuration changes
     * Terminates the old worker and creates a new one
     *
     * @param newProject The project to create the client for
     * @param serverPlugins The list of server plugins to initialize
     */
    private async recreateLanguageServer(newProject: Project | undefined, serverPlugins: ServerPlugin[]) {
        const languageClient = this.languageClient.value;
        this.languageClient.value = undefined;
        const glspClient = this.glspClient.value;
        this.glspClient.value = undefined;
        await nextTick();
        if (glspClient != undefined) {
            try {
                await glspClient.stop();
            } catch {
                // ignore errors during disposal
            }
        }
        if (languageClient != undefined) {
            try {
                await languageClient.stop();
                await languageClient.dispose();
            } catch {
                // ignore errors during disposal
            }
        }
        this.terminateWorker();

        if (newProject != undefined) {
            this.clientVersionCounter.value += 1;
            this.createWorkerAndSetupHandlers(newProject, serverPlugins);
        }
    }

    /**
     * Terminates the language server worker
     */
    private terminateWorker() {
        if (this.languageServerWorker != undefined) {
            this.languageServerWorker.terminate();
            this.languageServerWorker = undefined;
        }
    }

    /**
     * Creates a new worker and sets up message handlers
     *
     * @param project The project to create the worker for
     * @param serverPlugins The list of server plugins
     */
    private createWorkerAndSetupHandlers(project: Project, serverPlugins: ServerPlugin[]) {
        const worker = new Worker(new URL("../server/extensibleLangiumServer.ts", import.meta.url), {
            type: "module"
        });
        this.languageServerWorker = worker;

        const reader = new BrowserMessageReader(worker);
        const writer = new BrowserMessageWriter(worker);

        reader.listen((message: any) => {
            if (message.method === GetPluginsRequest.method && message.id != undefined) {
                this.handlePluginRequest(writer, message.id, serverPlugins);
            } else if (message.method === ServerReadyNotification.method) {
                this.createLanguageClient(reader, writer, project);
            }
        });
    }

    /**
     * Handles the plugin configuration request from the server
     *
     * @param writer The message writer
     * @param requestId The request ID to respond to
     * @param serverPlugins The list of server plugins to send
     */
    private handlePluginRequest(
        writer: BrowserMessageWriter,
        requestId: number | string,
        serverPlugins: ServerPlugin[]
    ) {
        const response: GetPluginsRequest.Response = {
            plugins: serverPlugins
        };
        const responseMessage: ResponseMessage = {
            jsonrpc: "2.0",
            id: requestId,
            result: response
        };
        writer.write(responseMessage);
    }

    /**
     * Creates and starts the Monaco language client
     *
     * @param reader The message reader
     * @param writer The message writer
     * @param project The project to initialize the client for
     */
    private createLanguageClient(reader: BrowserMessageReader, writer: BrowserMessageWriter, project: Project) {
        this.diagnosticStore.clear();

        const languageClient = new MonacoLanguageClient({
            name: "Extensible Language Client",
            clientOptions: {
                documentSelector: this.languagePlugins.value.map((plugin) => ({ language: plugin.id })),
                errorHandler: {
                    error: () => ({ action: ErrorAction.Continue }),
                    closed: () => ({ action: CloseAction.DoNotRestart })
                },
                workspaceFolder: {
                    uri: Uri.file(`/${project.id}/files`),
                    name: project.name,
                    index: 0
                },
                middleware: {
                    handleDiagnostics: (uri, diagnostics, next) => {
                        this.diagnosticStore.set(uri, diagnostics);
                        next(uri, diagnostics);
                    }
                }
            },
            messageTransports: { reader, writer }
        });
        this.languageClient.value = languageClient;

        this.glspClient.value = new MonacoGLSPClient({
            client: languageClient,
            id: "workbench-glsp-client"
        });

        this.registerFileSystemHandlers(languageClient);

        this.registerActionHandlers(languageClient);

        languageClient.start();

        setTimeout(async () => {
            if (this.languageClient.value?.state === State.Starting) {
                await this.recreateLanguageServer(project, this.serverPlugins.value);
            }
        }, 1000);
    }

    /**
     * Extracts the path from a URI.
     * Expected format for regular files: schema://projectId/files/path
     * Returns the path after removing projectId and /files/ prefix
     */
    private extractPath(uri: Uri): string {
        const fullPath = uri.path;
        const parts = fullPath.substring(1).split("/");

        if (parts[1] === "files") {
            const path = "/" + parts.slice(2).join("/");
            return path;
        }

        const path = "/" + parts.slice(1).join("/");
        return path;
    }

    /**
     * Registers file system request handlers on the language client
     *
     * @param client The language client to register handlers on
     */
    private registerFileSystemHandlers(client: MonacoLanguageClient) {
        client.onRequest(ReadFileRequest.type, async (params: ReadFileParams): Promise<string> => {
            const uri = Uri.parse(params.uri);
            const result = await this.monacoApi.fileService.readFile(uri);
            return result.value.toString();
        });

        client.onRequest(StatRequest.type, async (params: StatParams): Promise<StatResponse> => {
            const uri = Uri.parse(params.uri);
            const stat = await this.monacoApi.fileService.resolve(uri, { resolveMetadata: false });
            return {
                isFile: stat.isFile,
                isDirectory: stat.isDirectory,
                uri: params.uri
            };
        });

        client.onRequest(ReadDirectoryRequest.type, async (params: ReadDirectoryParams): Promise<FileSystemNode[]> => {
            const uri = Uri.parse(params.uri);
            const stat = await this.monacoApi.fileService.resolve(uri, { resolveMetadata: false });
            if (!stat.children) {
                throw new Error("Not a directory");
            }
            return stat.children.map((child) => ({
                isFile: child.isFile,
                isDirectory: child.isDirectory,
                uri: child.resource.toString()
            }));
        });

        client.onRequest(ReadMetadataRequest.type, async (params: ReadMetadataParams): Promise<object> => {
            const uri = Uri.parse(params.uri);
            const parsed = parseUri(uri);

            let result;
            if (parsed.category === FileCategory.ExecutionResultFile) {
                result = await this.backendApi.executions.readExecutionFileMetadata(
                    parsed.projectId,
                    parsed.executionId,
                    parsed.path
                );
            } else {
                result = await this.backendApi.files.readMetadata(this.project.value!.id, this.extractPath(uri));
            }

            if (result.success) {
                return result.value;
            }
            throw new Error("Failed to read metadata");
        });

        client.onRequest(WriteMetadataRequest.type, async (params: WriteMetadataParams): Promise<void> => {
            const uri = Uri.parse(params.uri);
            const parsed = parseUri(uri);

            let result;
            if (parsed.category === FileCategory.ExecutionResultFile) {
                result = await this.backendApi.executions.writeExecutionFileMetadata(
                    parsed.projectId,
                    parsed.executionId,
                    parsed.path,
                    params.metadata
                );
            } else {
                result = await this.backendApi.files.writeMetadata(
                    this.project.value!.id,
                    this.extractPath(uri),
                    params.metadata
                );
            }

            if (!result.success) {
                throw new Error("Failed to write metadata");
            }
        });
    }

    /**
     * Registers action notification handlers on the language client.
     * Handles notifications from the language server to trigger action dialogs
     * and to reveal source ranges in the Monaco textual editor.
     *
     * @param client The language client to register handlers on
     */
    private registerActionHandlers(client: MonacoLanguageClient) {
        client.onNotification(TriggerActionNotification.type, (params) => {
            this.pendingAction.value = params;
        });

        client.onNotification(RevealSourceNotification.type, async (params) => {
            await this.monacoApi.editorService.openEditor({
                resource: Uri.parse(params.uri),
                options: {
                    pinned: false,
                    selection: {
                        startLineNumber: params.range.start.line + 1,
                        startColumn: params.range.start.character + 1,
                        endLineNumber: params.range.end.line + 1,
                        endColumn: params.range.end.character + 1
                    }
                }
            });
        });
    }
}

/**
 * Type for the sidebar
 */
export type SidebarType = "files" | "search" | "projects" | "executions";
