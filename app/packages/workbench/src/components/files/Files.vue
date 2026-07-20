<template>
    <div class="flex flex-col h-full">
        <SidebarPanelHeader label="Files" />
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
                        v-for="fileType in languagePlugins.filter((plugin) => !plugin.isGenerated)"
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
                </ContextMenuContent>
            </ContextMenu>
        </ScrollArea>
    </div>
</template>

<script setup lang="ts">
import { ref, inject, watch, nextTick, useTemplateRef } from "vue";
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
import type { DragAndDropCallbacks } from "@/components/tree/util";
import FileSystemItemList, { type NewItemState } from "./FileSystemItemList.vue";
import type { ResolvedWorkbenchLanguagePlugin } from "@/data/plugin/plugin";
import ScrollArea from "../ui/scroll-area/ScrollArea.vue";
import { VSBuffer } from "@codingame/monaco-vscode-api/vscode/vs/base/common/buffer";
import { workbenchStateKey } from "../workbench/util";
import { Uri } from "vscode";
import { FileType } from "@codingame/monaco-vscode-files-service-override";
import { findFileInTree } from "@/data/filesystem/util";
import { FolderIcon, DownloadIcon } from "lucide-vue-next";
import FileTypeIcon from "../FileTypeIcon.vue";
import type { EditorTab } from "@/data/tab/editorTab";
import { FileCategory, parseUri } from "@mdeo/language-common";
import { downloadFolderAsZip } from "@/lib/zip";

const workbenchState = inject(workbenchStateKey)!;
const { fileTree: rootFolder, activeTab, monacoApi, languagePlugins, tabs } = workbenchState;

const activeEntry = ref<FileSystemNode>();
const expandedItems = ref<Set<FileSystemNode>>(new Set());

const newItem = ref<NewItemState>();

const treeRef = useTemplateRef("treeRef");

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

async function handleDownloadProject() {
    await downloadFolderAsZip(monacoApi, rootFolder, rootFolder.name);
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
    }
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
