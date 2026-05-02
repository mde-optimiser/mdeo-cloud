<template>
    <ContextMenu @update:open="handleContextMenuOpen">
        <ContextMenuTrigger as-child>
            <TreeItem
                :data="entry"
                :is-folder="isDirectory"
                :has-children="isDirectory"
                mode="default"
                @click="openTab(true, $event)"
                @dblclick="openTab(false, $event)"
                @keydown="handleKeydown"
            >
                <template #content>
                    <FolderIcon v-if="isDirectory" class="size-4 shrink-0" />
                    <FileTypeIcon v-else :model-value="fileTypePlugin" class="size-4 shrink-0" />
                    <span class="truncate">{{ entry.name }}</span>
                </template>
                <template v-if="isDirectory" #items>
                    <ExecutionFileItem
                        v-for="child in sortedChildren"
                        :key="child.uri.toString()"
                        :entry="child"
                        :execution-id="executionId"
                        @select="$emit('select', $event)"
                    />
                </template>
            </TreeItem>
        </ContextMenuTrigger>
        <ContextMenuContent
            v-if="!isDirectory || isMarkdownReportFolder"
            @close-auto-focus="$event.preventDefault()"
        >
            <ContextMenuItem
                v-for="action in contextMenuActions"
                :key="action.key"
                @click="() => handleFileAction(action)"
            >
                <Icon :iconNode="action.icon" :name="action.key" class="size-4 mr-2" />
                <span>{{ action.name }}</span>
            </ContextMenuItem>
            <ContextMenuItem @click="handleDownload">
                <DownloadIcon class="size-4 mr-2" />
                <span>Download</span>
            </ContextMenuItem>
        </ContextMenuContent>
    </ContextMenu>
</template>

<script setup lang="ts">
import { computed, inject, ref } from "vue";
import { FolderIcon, DownloadIcon, Icon } from "lucide-vue-next";
import TreeItem from "@/components/tree/TreeItem.vue";
import FileTypeIcon from "@/components/FileTypeIcon.vue";
import { ContextMenu, ContextMenuTrigger, ContextMenuContent, ContextMenuItem } from "@/components/ui/context-menu";
import type { FileSystemNode, File, Folder } from "@/data/filesystem/file";
import { FileType } from "vscode";
import { workbenchStateKey } from "@/components/workbench/util";
import { ActionDisplayLocation, type FileAction } from "@mdeo/language-common";
import { fetchFileActions as fetchAvailableFileActions, triggerFileAction } from "@/components/action/fileActions";

const props = defineProps<{
    entry: FileSystemNode;
    executionId: string;
}>();

const emit = defineEmits<{
    select: [entry: FileSystemNode];
}>();

const workbenchState = inject(workbenchStateKey)!;
const { languagePluginByExtension, languageClient, pendingAction, monacoApi, activeTab } = workbenchState;

const fileActions = ref<FileAction[]>([]);

const contextMenuActions = computed(() =>
    fileActions.value.filter((action) => action.displayLocations.includes(ActionDisplayLocation.CONTEXT_MENU))
);

const itemId = computed(() => props.entry.uri.toString());

const isDirectory = computed(() => props.entry.type === FileType.Directory);

const isMarkdownReportFolder = computed(() => {
    if (!isDirectory.value) return false;
    const folder = props.entry as Folder;
    return folder.children.some((c) => c.type === FileType.File && (c as File).extension === ".md");
});

const fileExtension = computed(() => {
    if (isDirectory.value) {
        return "";
    }
    return (props.entry as File).extension;
});

const fileTypePlugin = computed(() => {
    return languagePluginByExtension.value.get(fileExtension.value);
});

const sortedChildren = computed(() => {
    if (isDirectory.value == undefined) {
        return [];
    }

    const folder = props.entry as Folder;

    return [...folder.children].sort((a, b) => {
        const aIsDir = a.type === FileType.Directory;
        const bIsDir = b.type === FileType.Directory;

        if (aIsDir !== bIsDir) {
            return aIsDir ? -1 : 1;
        }
        return a.name.localeCompare(b.name);
    });
});

async function handleContextMenuOpen(open: boolean): Promise<void> {
    if (open && !isDirectory.value) {
        await fetchFileActions();
    }
}

async function fetchFileActions(): Promise<void> {
    const languagePlugin = languagePluginByExtension.value.get(fileExtension.value);
    if (!languagePlugin) {
        fileActions.value = [];
        return;
    }

    fileActions.value = await fetchAvailableFileActions(
        {
            languageClient,
            languagePluginByExtension
        },
        props.entry.uri.toString(),
        fileExtension.value
    );
}

function handleFileAction(action: FileAction): void {
    triggerFileAction(
        pendingAction,
        languagePluginByExtension,
        action,
        props.entry.uri.toString(),
        fileExtension.value
    );
}

async function handleDownload(): Promise<void> {
    let downloadUri = props.entry.uri;
    let downloadName = props.entry.name;

    if (isMarkdownReportFolder.value) {
        const folder = props.entry as Folder;
        const mdFile = folder.children.find(
            (c) => c.type === FileType.File && (c as File).extension === ".md"
        ) as File | undefined;
        if (!mdFile) return;
        downloadUri = mdFile.uri;
        downloadName = mdFile.name;
    }

    const result = await monacoApi.fileService.readFile(downloadUri);
    const content = result.value.toString();
    const blob = new Blob([content], { type: "application/octet-stream" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = downloadName;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}

async function openTab(temporary: boolean, event?: MouseEvent | KeyboardEvent) {
    if (props.entry.type === FileType.File) {
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

function handleKeydown(event: KeyboardEvent) {
    if (event.key === "Enter") {
        openTab(false, event);
    }
}
</script>
