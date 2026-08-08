<template>
    <div class="flex flex-col h-full">
        <SidebarPanelHeader label="Files">
            <template #actions>
                <div class="flex items-center">
                    <span class="inline-flex" @mouseenter="openNewFileMenu" @mouseleave="scheduleCloseNewFileMenu">
                        <DropdownMenu v-model:open="newFileMenuOpen" :modal="false">
                            <DropdownMenuTrigger as-child>
                                <Button
                                    variant="ghost"
                                    size="icon"
                                    class="h-8 w-8"
                                    aria-label="New File"
                                    :disabled="creatableFileTypes.length === 0"
                                >
                                    <FilePlusIcon class="size-4" />
                                </Button>
                            </DropdownMenuTrigger>
                            <DropdownMenuContent
                                align="start"
                                @open-auto-focus="handleNewFileMenuOpenAutoFocus"
                                @close-auto-focus="$event.preventDefault()"
                                @focus-outside="handleNewFileMenuFocusOutside"
                                @mouseenter="openNewFileMenu"
                                @mouseleave="scheduleCloseNewFileMenu"
                            >
                                <DropdownMenuItem
                                    v-for="fileType in creatableFileTypes"
                                    :key="fileType.id"
                                    @click="() => handleNewFile(fileType)"
                                >
                                    <FileTypeIcon :model-value="fileType" />
                                    <span>New {{ fileType.name }}</span>
                                </DropdownMenuItem>
                            </DropdownMenuContent>
                        </DropdownMenu>
                    </span>
                    <Tooltip>
                        <TooltipTrigger as-child>
                            <Button variant="ghost" size="icon" class="h-8 w-8" @click="handleNewFolder">
                                <FolderPlusIcon class="size-4" />
                            </Button>
                        </TooltipTrigger>
                        <TooltipContent side="right">New Folder</TooltipContent>
                    </Tooltip>
                </div>
            </template>
        </SidebarPanelHeader>
        <ScrollArea class="files-container flex-1 min-h-0 w-full">
            <ContextMenu>
                <ContextMenuTrigger as-child>
                    <Tree
                        ref="treeRef"
                        class="flex-1 w-full p-2"
                        :active-element="activeEntry"
                        :enable-drag-and-drop="true"
                        :drag-and-drop-callbacks="dragAndDropCallbacks"
                        :expanded-items="expandedItems"
                    >
                        <FileSystemItemList
                            v-if="rootFolder"
                            :parent="rootFolder"
                            v-model:new-item="newItem"
                            @select="handleSelect"
                            @create-file="handleCreateFile"
                            @create-folder="handleCreateFolder"
                            @rename="handleRename"
                            @delete="handleDelete"
                        />
                    </Tree>
                </ContextMenuTrigger>
                <ContextMenuContent @close-auto-focus="$event.preventDefault()">
                    <ContextMenuItem
                        v-for="fileType in creatableFileTypes"
                        :key="fileType.id"
                        @click="() => handleCreateFileOfType(fileType)"
                    >
                        <FileTypeIcon :model-value="fileType" />
                        <span>Create New {{ fileType.name }}</span>
                    </ContextMenuItem>
                    <ContextMenuSeparator />
                    <ContextMenuItem @click="handleCreateFolderFromContext">
                        <FolderIcon />
                        <span>Create New Folder</span>
                    </ContextMenuItem>
                    <ContextMenuSeparator />
                    <ContextMenuItem @click="handleDownloadProject">
                        <DownloadIcon class="size-4 mr-2" />
                        <span>Download</span>
                    </ContextMenuItem>
                    <ContextMenuItem @click="handleUploadClick">
                        <UploadIcon />
                        <span>Upload File...</span>
                    </ContextMenuItem>
                </ContextMenuContent>
            </ContextMenu>
        </ScrollArea>
        <input
            ref="fileInputRef"
            type="file"
            :accept="acceptedExtensions"
            multiple
            class="hidden"
            @change="handleFileInputChange"
        />
    </div>
</template>

<script setup lang="ts">
import { ref, inject, watch, computed, nextTick, onBeforeUnmount, provide, useTemplateRef } from "vue";
import Tree from "@/components/tree/Tree.vue";
import SidebarPanelHeader from "@/components/sidebar/SidebarPanelHeader.vue";
import {
    ContextMenu,
    ContextMenuTrigger,
    ContextMenuContent,
    ContextMenuItem,
    ContextMenuSeparator
} from "@/components/ui/context-menu";
import type { FileSystemNode, Folder } from "@/data/filesystem/file";
import type { DragAndDropCallbacks, TreeItem } from "@/components/tree/util";
import FileSystemItemList, { type NewItemState } from "./FileSystemItemList.vue";
import { newFileSystemItemStateKey, type NewFileSystemItemState } from "./util";
import { Button } from "@/components/ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger
} from "@/components/ui/dropdown-menu";
import type { ResolvedWorkbenchLanguagePlugin } from "@/data/plugin/plugin";
import ScrollArea from "../ui/scroll-area/ScrollArea.vue";
import { VSBuffer } from "@codingame/monaco-vscode-api/vscode/vs/base/common/buffer";
import { workbenchStateKey } from "../workbench/util";
import { Uri } from "vscode";
import { FileType } from "@codingame/monaco-vscode-files-service-override";
import { findFileInTree } from "@/data/filesystem/util";
import { FolderIcon, FilePlusIcon, FolderPlusIcon, DownloadIcon, UploadIcon } from "@lucide/vue";
import FileTypeIcon from "../FileTypeIcon.vue";
import type { EditorTab } from "@/data/tab/editorTab";
import { FileCategory, parseUri } from "@mdeo/language-common";
import { downloadFolderAsZip } from "@/lib/zip";
import { uploadFiles } from "@/data/filesystem/uploadFiles";

const workbenchState = inject(workbenchStateKey)!;
const { fileTree: rootFolder, activeTab, monacoApi, languagePlugins, languagePluginByExtension, tabs } =
    workbenchState;

const activeEntry = ref<FileSystemNode>();
const expandedItems = ref<Set<FileSystemNode>>(new Set());

/**
 * The item which is currently being created, shared with all items of the file tree,
 * as at most one item can be created at a time.
 */
const newFileSystemItemState = ref<NewFileSystemItemState>();
provide(newFileSystemItemStateKey, newFileSystemItemState);

/**
 * The new item to create, only set if it is created in the root folder
 */
const newItem = computed<NewItemState | undefined>({
    get() {
        return isNewItemInRoot() ? newFileSystemItemState.value : undefined;
    },
    set(value) {
        if (value == undefined) {
            if (isNewItemInRoot()) {
                newFileSystemItemState.value = undefined;
            }
        } else {
            newFileSystemItemState.value = { ...value, parent: rootFolder };
        }
    }
});

/**
 * Checks if the item currently being created is created in the root folder
 */
function isNewItemInRoot(): boolean {
    const state = newFileSystemItemState.value;
    return state != undefined && state.parent.uri.toString() === rootFolder.uri.toString();
}

/**
 * All file types which can be created by the user
 */
const creatableFileTypes = computed(() => languagePlugins.value.filter((plugin) => !plugin.isGenerated));

const newFileMenuOpen = ref(false);
const newFileMenuOpenedByHover = ref(false);
let newFileMenuCloseTimeout: number | undefined;

watch(newFileMenuOpen, (open) => {
    if (!open) {
        newFileMenuOpenedByHover.value = false;
    }
});

const treeRef = useTemplateRef("treeRef");
const fileInputRef = useTemplateRef("fileInputRef");

watch(
    activeTab,
    (newTab) => {
        if (newTab == undefined) {
            activeEntry.value = undefined;
            return;
        }
        const parsed = parseUri(newTab.fileUri);
        if (parsed.category !== FileCategory.RegularFile) {
            activeEntry;
        }

        if (parsed.category === FileCategory.RegularFile) {
            const file = findFileInTree(rootFolder, parsed.path);
            if (file == undefined) {
                activeEntry.value = undefined;
                return;
            }
            activeEntry.value = file;
            let parent = file.parent;
            while (parent != undefined) {
                expandedItems.value.add(parent);
                parent = parent.parent;
            }
            nextTick(() => {
                const element = treeRef.value!.$el.querySelector(`[data-active=true]`) as HTMLElement;
                if (element != undefined) {
                    const rect = element.getBoundingClientRect();
                    const completelyVisible =
                        rect.top >= 0 && rect.bottom <= (window.innerHeight || document.documentElement.clientHeight);
                    if (!completelyVisible) {
                        element.scrollIntoView({ block: "center", behavior: "instant" });
                    }
                }
            });
        } else {
            activeEntry.value = undefined;
        }
    },
    { immediate: true }
);

function handleSelect(entry: FileSystemNode) {
    activeEntry.value = entry;
}

async function handleCreateFile(uri: Uri, fileType: ResolvedWorkbenchLanguagePlugin) {
    const fileService = monacoApi.fileService;
    await fileService.createFile(uri, VSBuffer.fromString(""));
    await nextTick();

    const existingTab = tabs.value.find((tab) => tab.fileUri.toString() === uri.toString());

    if (existingTab) {
        workbenchState.activeTab.value = existingTab;
        existingTab.temporary = false;
    } else {
        const newTab: EditorTab = {
            fileUri: uri,
            temporary: false
        };
        tabs.value.push(newTab);
        workbenchState.activeTab.value = newTab;
    }

    if (fileType.newFileAction === true) {
        workbenchState.pendingAction.value = {
            type: "new-file",
            languageId: fileType.id,
            data: {
                uri: uri.toString()
            }
        };
    }
}

async function handleCreateFolder(uri: Uri) {
    const fileService = monacoApi.fileService;
    await fileService.createFolder(uri);
}

async function handleRename(oldUri: Uri, newUri: Uri) {
    const fileService = monacoApi.fileService;
    await fileService.move(oldUri, newUri, false);
}

async function handleDelete(uri: Uri) {
    const fileService = monacoApi.fileService;
    await fileService.del(uri, { recursive: true });
}

async function handleMove(itemUri: Uri, targetFolderUri: Uri) {
    const fileService = monacoApi.fileService;
    const fileName = itemUri.path.split("/").pop() || "";
    const newUri = Uri.joinPath(targetFolderUri, fileName);
    await fileService.move(itemUri, newUri, false);
}

function handleCreateFileOfType(fileType: ResolvedWorkbenchLanguagePlugin) {
    newItem.value = {
        type: "file",
        fileType
    };
}

function handleCreateFolderFromContext() {
    newItem.value = {
        type: "folder"
    };
}

function handleNewFile(fileType: ResolvedWorkbenchLanguagePlugin) {
    startNewItem({
        type: "file",
        fileType
    });
}

function handleNewFolder() {
    startNewItem({
        type: "folder"
    });
}

/**
 * Starts the creation of a new item in the currently selected folder
 *
 * @param item the item to create, without the folder it is created in
 */
function startNewItem(item: Omit<NewFileSystemItemState, "parent">) {
    const parent = selectedFolder();
    newFileSystemItemState.value = { ...item, parent };
    let folder: Folder | null = parent;
    while (folder != undefined) {
        expandedItems.value.add(folder);
        folder = folder.parent;
    }
}

/**
 * Provides the folder new items are created in: the selected folder, the folder containing the
 * selected file, or the root folder if nothing is selected.
 */
function selectedFolder(): Folder {
    const entry = activeEntry.value;
    if (entry == undefined) {
        return rootFolder;
    }
    return entry.type === FileType.Directory ? entry : (entry.parent ?? rootFolder);
}

function openNewFileMenu() {
    if (creatableFileTypes.value.length === 0) {
        return;
    }
    clearTimeout(newFileMenuCloseTimeout);
    if (!newFileMenuOpen.value) {
        newFileMenuOpenedByHover.value = true;
    }
    newFileMenuOpen.value = true;
}

/**
 * Keeps the focus where it is if the menu was opened by hovering, as taking it away from the editor
 * on mere hover is disruptive. If the menu was opened via keyboard, it is focused so it can be
 * navigated with the arrow keys.
 */
function handleNewFileMenuOpenAutoFocus(event: Event) {
    if (newFileMenuOpenedByHover.value) {
        event.preventDefault();
    }
}

/**
 * Only keeps the menu open on focus changes outside of it if it was opened by hovering, where the
 * focus was intentionally left where it was. A menu opened via keyboard has the focus inside it, so
 * moving the focus away has to dismiss it as usual.
 */
function handleNewFileMenuFocusOutside(event: Event) {
    if (newFileMenuOpenedByHover.value) {
        event.preventDefault();
    }
}

function scheduleCloseNewFileMenu() {
    clearTimeout(newFileMenuCloseTimeout);
    newFileMenuCloseTimeout = window.setTimeout(() => {
        newFileMenuOpen.value = false;
    }, 200);
}

onBeforeUnmount(() => clearTimeout(newFileMenuCloseTimeout));

async function handleDownloadProject() {
    await downloadFolderAsZip(monacoApi, rootFolder, rootFolder.name);
}

/**
 * The file picker's `accept` attribute, listing every extension a registered
 * language plugin handles, so the OS dialog only offers files that can
 * actually be uploaded.
 */
const acceptedExtensions = computed(() => Array.from(languagePluginByExtension.value.keys()).join(","));

function handleUploadClick() {
    fileInputRef.value?.click();
}

async function handleFileInputChange(event: Event) {
    const input = event.target as HTMLInputElement;
    if (input.files != undefined && input.files.length > 0) {
        await uploadFiles(input.files, rootFolder.uri, monacoApi.fileService, tabs, activeTab, languagePluginByExtension);
    }
    input.value = "";
}

async function handleFilesDropped(files: FileList, targetItem: TreeItem | undefined) {
    const targetNode = targetItem as FileSystemNode | undefined;
    const targetFolderUri =
        targetNode != undefined && targetNode.type === FileType.Directory ? targetNode.uri : rootFolder.uri;
    await uploadFiles(files, targetFolderUri, monacoApi.fileService, tabs, activeTab, languagePluginByExtension);
}

const dragAndDropCallbacks: DragAndDropCallbacks = {
    canDrop: (draggedItemId, targetItem) => {
        const draggedNode = findFileInRoot(Uri.file(draggedItemId));
        if (draggedNode == undefined) {
            return false;
        }
        const targetNode = targetItem as FileSystemNode;

        if (targetNode.type !== FileType.Directory) {
            return false;
        }

        if (draggedNode.uri.toString() === targetNode.uri.toString()) {
            return false;
        }

        return true;
    },

    onDrop: async (draggedItemId, targetItem) => {
        const draggedNode = findFileInRoot(Uri.file(draggedItemId));
        if (draggedNode == undefined) {
            return;
        }
        const targetNode = targetItem as FileSystemNode;

        if (targetNode.type !== FileType.Directory) {
            return;
        }
        if (draggedNode.type === FileType.Directory) {
            let current: Folder | null = targetNode;
            while (current != null) {
                if (current.uri.toString() === draggedNode.uri.toString()) {
                    return;
                }
                current = current.parent;
            }
        }

        handleMove(draggedNode.uri, targetNode.uri);
    },

    onTreeDrop: async (draggedItem) => {
        const draggedNode = findFileInRoot(Uri.file(draggedItem));
        if (draggedNode == undefined) {
            return;
        }
        if (draggedNode.parent?.uri.toString() === rootFolder.uri.toString()) {
            return;
        }

        handleMove(draggedNode.uri, rootFolder.uri);
    },

    onFilesDropped: handleFilesDropped
};

function findFileInRoot(uri: Uri): FileSystemNode | undefined {
    const parsed = parseUri(uri);
    if (parsed.category !== FileCategory.RegularFile) {
        return undefined;
    }
    return findFileInTree(rootFolder, parsed.path);
}
</script>
<style scoped>
.files-container :deep(div[data-reka-scroll-area-viewport] > div:first-child) {
    @apply min-h-full flex flex-col;
}
</style>
