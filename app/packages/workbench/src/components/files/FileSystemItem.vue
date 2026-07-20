<template>
    <ContextMenu @update:open="handleContextMenuOpen">
        <ContextMenuTrigger as-child>
            <TreeItem
                :data="entry"
                :items="entry.type === FileType.Directory ? entry.children : undefined"
                :mode="isRenaming ? 'edit' : 'default'"
                :is-folder="entry.type === FileType.Directory"
                :has-children="entry.type === FileType.Directory && (entry.children.length > 0 || newItem != undefined)"
                @click="openTab(true, $event)"
                @dblclick="openTab(false, $event)"
                @keydown="handleKeydown"
            >
                <template #content>
                    <FileTypeIcon
                        v-if="entry.type === FileType.File"
                        :model-value="languagePluginByExtension.get(entry.extension)"
                        class="size-4"
                    />
                    <span v-if="isRenaming" class="flex flex-1">
                        <TreeItemInput
                            :model-value="getFileNameWithoutExtension(entry.name)"
                            :validate="validateRename"
                            @submit="handleRenameSubmit"
                            @cancel="handleRenameCancel"
                        />{{ getFileExtension(entry.name) }}
                    </span>
                    <span v-else :class="{ 'text-destructive': entryErrorCount > 0 }">{{ entry.name }}</span>
                    <span v-if="entryErrorCount > 0" class="ml-auto shrink-0 text-xs font-medium text-destructive">
                        {{ entryErrorCount > 9 ? "9+" : entryErrorCount }}
                    </span>
                </template>
                <template v-if="entry.type === FileType.Directory" #items>
                    <FileSystemItemList
                        :parent="entry"
                        v-model:new-item="newItem"
                        @select="$emit('select', $event)"
                        @create-file="handleCreateFileFromChild"
                        @create-folder="handleCreateFolderFromChild"
                        @rename="handleRenameFromChild"
                        @delete="handleDeleteFromChild"
                        @move="handleMoveFromChild"
                    />
                </template>
            </TreeItem>
        </ContextMenuTrigger>
        <ContextMenuContent @close-auto-focus="$event.preventDefault()">
            <template v-if="entry.type === FileType.File && fileActions.length > 0">
                <ContextMenuItem
                    v-for="action in contextMenuActions"
                    :key="action.key"
                    @click="() => handleFileAction(action)"
                >
                    <Icon :iconNode="action.icon" :name="action.key" class="size-4 mr-2" />
                    <span>{{ action.name }}</span>
                </ContextMenuItem>
                <ContextMenuSeparator />
            </template>
            <template v-if="entry.type === FileType.Directory">
                <ContextMenuItem
                    v-for="fileType in languagePlugins.filter((plugin) => !plugin.isGenerated)"
                    :key="fileType.id"
                    @click="() => handleCreateFileOfType(fileType)"
                >
                    <FileTypeIcon :model-value="fileType" />
                    <span>Create New {{ fileType.name }}</span>
                </ContextMenuItem>
                <ContextMenuSeparator />
                <ContextMenuItem @click="handleCreateFolder">
                    <FolderIcon />
                    <span>Create New Folder</span>
                </ContextMenuItem>
                <ContextMenuSeparator />
            </template>
            <ContextMenuItem @click="handleDownload">
                <DownloadIcon class="size-4 mr-2" />
                <span>Download</span>
            </ContextMenuItem>
            <ContextMenuSeparator />
            <ContextMenuItem @click="handleRename">
                <EditIcon />
                <span>Rename</span>
            </ContextMenuItem>
            <ContextMenuItem @click="handleDeleteClick">
                <Trash2Icon />
                <span>Delete</span>
            </ContextMenuItem>
        </ContextMenuContent>
    </ContextMenu>

    <AlertDialog v-model:open="showDeleteDialog">
        <AlertDialogContent>
            <AlertDialogHeader>
                <AlertDialogTitle>Delete Folder</AlertDialogTitle>
                <AlertDialogDescription>
                    Are you sure you want to delete the folder "{{ entry.name }}" and all its contents? This action
                    cannot be undone.
                </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
                <AlertDialogCancel>Cancel</AlertDialogCancel>
                <AlertDialogAction @click="confirmDelete">Delete</AlertDialogAction>
            </AlertDialogFooter>
        </AlertDialogContent>
    </AlertDialog>
</template>

<script setup lang="ts">
import { ref, inject, computed } from "vue";
import { FolderIcon, EditIcon, Trash2Icon, DownloadIcon, Icon } from "lucide-vue-next";
import TreeItem from "@/components/tree/TreeItem.vue";
import TreeItemInput from "../tree/TreeItemInput.vue";
import FileSystemItemList from "./FileSystemItemList.vue";
import {
    ContextMenu,
    ContextMenuTrigger,
    ContextMenuContent,
    ContextMenuItem,
    ContextMenuSeparator
} from "@/components/ui/context-menu";
import {
    AlertDialog,
    AlertDialogAction,
    AlertDialogCancel,
    AlertDialogContent,
    AlertDialogDescription,
    AlertDialogFooter,
    AlertDialogHeader,
    AlertDialogTitle
} from "@/components/ui/alert-dialog";
import { type FileSystemNode } from "@/data/filesystem/file";
import type { NewItemState } from "./FileSystemItemList.vue";
import type { ResolvedWorkbenchLanguagePlugin } from "@/data/plugin/plugin";
import { workbenchStateKey } from "@/components/workbench/util";
import { treeContextKey } from "../tree/util";
import { FileType } from "@codingame/monaco-vscode-files-service-override";
import { Uri } from "vscode";
import FileTypeIcon from "../FileTypeIcon.vue";
import { ActionDisplayLocation, type FileAction } from "@mdeo/language-common";
import { getFileExtension } from "@/data/filesystem/util";
import { downloadFolderAsZip, downloadBlob } from "@/lib/zip";
import plugin from "vue-sonner";
import { fetchFileActions as fetchAvailableFileActions, triggerFileAction } from "@/components/action/fileActions";

const props = defineProps<{
    entry: FileSystemNode;
}>();

const workbenchState = inject(workbenchStateKey)!;
const {
    monacoApi,
    languagePlugins,
    activeTab,
    languagePluginByExtension,
    languageClient,
    pendingAction,
    diagnosticStore
} = workbenchState;
const treeContext = inject(treeContextKey)!;

const emit = defineEmits<{
    select: [entry: FileSystemNode];
    createFile: [uri: Uri, fileType: ResolvedWorkbenchLanguagePlugin];
    createFolder: [uri: Uri];
    rename: [oldUri: Uri, newUri: Uri];
    delete: [uri: Uri];
    move: [itemUri: Uri, targetFolderUri: Uri];
    delegateCreateFile: [fileType: ResolvedWorkbenchLanguagePlugin];
    delegateCreateFolder: [];
}>();

const isRenaming = ref(false);
const showDeleteDialog = ref(false);
const newItem = ref<NewItemState>();
const fileActions = ref<FileAction[]>([]);

const contextMenuActions = computed(() =>
    fileActions.value.filter((action) => action.displayLocations.includes(ActionDisplayLocation.CONTEXT_MENU))
);

const entryErrorCount = computed(() => {
    if (props.entry.type === FileType.File) {
        return diagnosticStore.fileDiagnostics.value.get(props.entry.uri.toString())?.errors ?? 0;
    }
    // For folders, aggregate error counts from all descendant files
    const prefix = props.entry.uri.toString() + "/";
    let total = 0;
    for (const [uri, summary] of diagnosticStore.fileDiagnostics.value) {
        if (uri.startsWith(prefix)) {
            total += summary.errors;
        }
    }
    return total;
});

async function handleContextMenuOpen(open: boolean): Promise<void> {
    if (open && props.entry.type === FileType.File) {
        await fetchFileActions();
    }
}

async function fetchFileActions(): Promise<void> {
    if (props.entry.type !== FileType.File) {
        fileActions.value = [];
        return;
    }

    fileActions.value = await fetchAvailableFileActions(
        {
            languageClient,
            languagePluginByExtension
        },
        props.entry.uri.toString(),
        props.entry.extension
    );
}

function handleFileAction(action: FileAction): void {
    if (props.entry.type !== FileType.File) {
        return;
    }

    triggerFileAction(
        pendingAction,
        languagePluginByExtension,
        action,
        props.entry.uri.toString(),
        props.entry.extension
    );
}

async function openTab(temporary: boolean, event?: MouseEvent | KeyboardEvent) {
    if (props.entry.type === FileType.File && !isRenaming.value) {
        const file = props.entry;

        if (event instanceof KeyboardEvent) {
            event.preventDefault();
        }
        await monacoApi.editorService.openEditor({
            resource: file.uri,
            options: {
                preserveFocus: temporary
            }
        });
        if (!temporary && activeTab.value != undefined) {
            activeTab.value.temporary = false;
        }
    }
    emit("select", props.entry);
}

function handleCreateFileOfType(fileType: ResolvedWorkbenchLanguagePlugin) {
    if (props.entry.type === FileType.Directory) {
        newItem.value = {
            type: "file",
            fileType
        };
        treeContext.expandedItems.value.add(props.entry);
    } else {
        emit("delegateCreateFile", fileType);
    }
}

function handleCreateFolder() {
    if (props.entry.type === FileType.Directory) {
        newItem.value = {
            type: "folder"
        };
        treeContext.expandedItems.value.add(props.entry);
    } else {
        emit("delegateCreateFolder");
    }
}

function handleKeydown(event: KeyboardEvent) {
    if (event.key === "F2") {
        handleRename();
        event.stopPropagation();
    } else if (event.key === "Delete") {
        handleDeleteClick();
        event.stopPropagation();
    } else if (event.key === "Enter") {
        openTab(false, event);
        event.stopPropagation();
    }
}

function handleRename() {
    isRenaming.value = true;
}

function handleDeleteClick() {
    if (isRenaming.value) {
        return;
    }

    if (props.entry.type === FileType.Directory) {
        showDeleteDialog.value = true;
    } else {
        handleDelete();
    }
}

async function handleDownload(): Promise<void> {
    if (props.entry.type === FileType.Directory) {
        await downloadFolderAsZip(monacoApi, props.entry, props.entry.name);
        return;
    }
    const result = await monacoApi.fileService.readFile(props.entry.uri);
    downloadBlob(result.value.buffer, props.entry.name);
}

function handleDelete() {
    if (!isRenaming.value) {
        emit("delete", props.entry.uri);
    }
}

function confirmDelete() {
    handleDelete();
    showDeleteDialog.value = false;
}

function handleRenameSubmit(newName: string) {
    if (newName.trim() && newName !== getFileNameWithoutExtension(props.entry.name) && isRenaming.value) {
        const extension = getFileExtension(props.entry.name);
        const fullName = `${newName.trim()}${extension}`;
        const parent = props.entry.parent;
        const newUri = parent ? Uri.joinPath(parent.uri, fullName) : Uri.file(`/${fullName}`);
        emit("rename", props.entry.uri, newUri);
    }
    isRenaming.value = false;
}

function handleCreateFileFromChild(uri: Uri, fileType: ResolvedWorkbenchLanguagePlugin) {
    emit("createFile", uri, fileType);
}

function handleCreateFolderFromChild(uri: Uri) {
    emit("createFolder", uri);
}

function handleRenameFromChild(oldUri: Uri, newUri: Uri) {
    emit("rename", oldUri, newUri);
}

function handleDeleteFromChild(uri: Uri) {
    emit("delete", uri);
}

function handleMoveFromChild(itemUri: Uri, targetFolderUri: Uri) {
    emit("move", itemUri, targetFolderUri);
}

function handleRenameCancel() {
    isRenaming.value = false;
}

function getFileNameWithoutExtension(filename: string): string {
    const lastDotIndex = filename.lastIndexOf(".");
    return lastDotIndex > 0 ? filename.substring(0, lastDotIndex) : filename;
}

function validateRename(newName: string): boolean {
    if (newName.trim().length === 0) {
        return false;
    }
    const fullName = newName.trim() + getFileExtension(props.entry.name);
    if (fullName === props.entry.name) {
        return true;
    }
    const parent = props.entry.parent;
    return (
        parent?.children.every(
            (child) => child.uri.toString() === props.entry.uri.toString() || child.name !== fullName
        ) ?? true
    );
}
</script>
