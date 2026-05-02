<template>
    <ContextMenu>
        <ContextMenuTrigger>
            <HoverCard :open-delay="300">
                <HoverCardTrigger>
                    <TreeItem
                        :data="executionData"
                        :is-folder="isCompletedSuccessfully"
                        :has-children="isCompletedSuccessfully"
                        :mode="'default'"
                        @click="handleClick"
                        @dblclick="openTab(false, $event)"
                    >
                        <template #content>
                            <div class="flex items-center gap-2 shrink-0">
                                <ExecutionStatusIcon :state="executionData.execution.state" class="size-4 shrink-0" />
                            </div>
                            <span class="truncate flex-1 text-left">
                                {{ executionData.execution.name }}
                            </span>
                            <span
                                v-if="isCompleted"
                                class="shrink-0 p-0.5 rounded hover:bg-muted-foreground/20 transition-opacity flex items-center justify-center size-6 invisible group-hover/tree-button:visible pointer-events-auto!"
                                role="button"
                                tabindex="0"
                                @click.stop="openTab(true, $event)"
                                @dblclick.stop="openTab(false, $event)"
                                @keydown.enter.stop="openTab(false, $event)"
                            >
                                <FileText class="size-3.5" />
                            </span>
                        </template>
                        <template v-if="isCompletedSuccessfully" #items>
                            <template v-if="executionData.isLoadingTree">
                                <li class="flex items-center gap-2 p-2 text-sm text-muted-foreground">
                                    <Loader2 class="size-4 animate-spin" />
                                    <span>Loading files...</span>
                                </li>
                            </template>
                            <template v-else-if="executionData.fileTree && executionData.fileTree.children.length > 0">
                                <ExecutionFileItem
                                    v-for="file in sortedFileTree"
                                    :key="file.uri.toString()"
                                    :entry="file"
                                    :execution-id="executionData.execution.id"
                                    @select="$emit('select', $event)"
                                />
                            </template>
                            <template v-else-if="!isTerminalState(executionData.execution.state)">
                                <li class="flex items-center gap-2 p-2 text-sm text-muted-foreground">
                                    <Loader2 class="size-4 animate-spin" />
                                    <span>Execution in progress...</span>
                                </li>
                            </template>
                            <template v-else>
                                <li class="p-2 text-sm text-muted-foreground">No files</li>
                            </template>
                        </template>
                    </TreeItem>
                </HoverCardTrigger>

                <HoverCardContent side="right" :align="'start'" class="w-72 ml-3">
                    <div class="space-y-2">
                        <div class="flex items-center gap-2">
                            <ExecutionStatusIcon :state="executionData.execution.state" class="size-4 shrink-0" />
                            <span class="font-medium truncate">{{ executionData.execution.name }}</span>
                        </div>
                        <div class="text-sm text-muted-foreground space-y-1">
                            <div class="flex items-center gap-2">
                                <FileCode class="w-3.5 h-3.5 shrink-0" />
                                <span class="truncate">{{ executionData.execution.filePath }}</span>
                            </div>
                            <div class="flex items-center gap-2">
                                <Clock class="w-3.5 h-3.5 shrink-0" />
                                <span>Submitted {{ formattedCreatedAt }}</span>
                            </div>
                            <div v-if="executionData.execution.startedAt" class="flex items-center gap-2">
                                <Play class="w-3.5 h-3.5 shrink-0" />
                                <span>Started {{ startedTimeAgo }}</span>
                            </div>
                            <div v-if="formattedDuration" class="flex items-center gap-2">
                                <Timer class="w-3.5 h-3.5 shrink-0" />
                                <span>Duration: {{ formattedDuration }}</span>
                            </div>
                            <div
                                v-if="isRunning && executionData.execution.progressText"
                                class="flex items-center gap-2 pt-1"
                            >
                                <Loader2 class="w-3.5 h-3.5 shrink-0 animate-spin" />
                                <span class="text-xs">{{ executionData.execution.progressText }}</span>
                            </div>
                        </div>
                    </div>
                </HoverCardContent>
            </HoverCard>
        </ContextMenuTrigger>
        <ContextMenuContent @close-auto-focus="$event.preventDefault()">
            <ContextMenuItem v-if="isCompleted" @click="openTab(false)">
                <FileText class="size-4 mr-2" />
                <span>Open Report</span>
            </ContextMenuItem>
            <ContextMenuItem v-if="isCompleted" @click="handleDownloadReport">
                <Download class="size-4 mr-2" />
                <span>Download Report</span>
            </ContextMenuItem>
            <ContextMenuItem v-if="canCancel" @click="handleCancel">
                <XCircle class="size-4 mr-2" />
                <span>Cancel</span>
            </ContextMenuItem>
            <ContextMenuSeparator />
            <ContextMenuItem @click="handleDelete" class="text-destructive">
                <Trash2 class="size-4 mr-2" />
                <span>Delete</span>
            </ContextMenuItem>
        </ContextMenuContent>
    </ContextMenu>
</template>

<script setup lang="ts">
import { computed, inject, watch } from "vue";
import { FileText, Loader2, XCircle, Trash2, Download, FileCode, Clock, Play, Timer } from "lucide-vue-next";
import { useTimeAgo } from "@vueuse/core";
import TreeItem from "@/components/tree/TreeItem.vue";
import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/components/ui/hover-card";
import {
    ContextMenu,
    ContextMenuTrigger,
    ContextMenuContent,
    ContextMenuItem,
    ContextMenuSeparator
} from "@/components/ui/context-menu";
import { workbenchStateKey } from "@/components/workbench/util";
import { treeContextKey } from "@/components/tree/util";
import { isTerminalState, canCancelExecution } from "@/data/execution/execution";
import { FileType, Uri } from "vscode";
import type { ExecutionWithLoadedTree } from "@/data/workbenchState";
import ExecutionFileItem from "./ExecutionFileItem.vue";
import ExecutionStatusIcon from "./ExecutionStatusIcon.vue";
import type { FileSystemNode } from "@/data/filesystem/file";
import { formatDuration, calculateDuration } from "@/lib/duration";

const props = defineProps<{
    executionData: ExecutionWithLoadedTree;
}>();

const emit = defineEmits<{
    select: [entry: FileSystemNode | ExecutionWithLoadedTree];
    cancel: [executionId: string];
    delete: [executionData: ExecutionWithLoadedTree];
}>();

const workbenchState = inject(workbenchStateKey)!;
const { project, monacoApi, activeTab } = workbenchState;
const treeContext = inject(treeContextKey);
const canCancel = computed(() => canCancelExecution(props.executionData.execution.state));
const isCompletedSuccessfully = computed(() => props.executionData.execution.state === "completed");
const isCompleted = computed(() => isCompletedSuccessfully.value || props.executionData.execution.state === "failed");
const isRunning = computed(() => props.executionData.execution.state === "running");

const createdAtDate = computed(() => new Date(props.executionData.execution.createdAt));
const formattedCreatedAt = useTimeAgo(createdAtDate);

const startedAtDate = computed(() => {
    const startedAt = props.executionData.execution.startedAt;
    return startedAt ? new Date(startedAt) : new Date();
});
const startedTimeAgo = useTimeAgo(startedAtDate);

const formattedDuration = computed(() => {
    const { startedAt, finishedAt } = props.executionData.execution;
    const duration = calculateDuration(startedAt, finishedAt);
    return duration !== null ? formatDuration(duration) : null;
});

const sortedFileTree = computed(() => {
    if (props.executionData.fileTree == undefined) {
        return [];
    }

    return [...props.executionData.fileTree.children].sort((a, b) => {
        const aIsDir = a.type === FileType.Directory;
        const bIsDir = b.type === FileType.Directory;

        if (aIsDir !== bIsDir) {
            return aIsDir ? -1 : 1;
        }
        return a.name.localeCompare(b.name);
    });
});

function handleClick() {
    emit("select", props.executionData);
}

async function openTab(temporary: boolean, event?: MouseEvent | KeyboardEvent) {
    if (event instanceof KeyboardEvent) {
        event.preventDefault();
    }

    const reportUri = Uri.file(`/${project.value!.id}/executions/${props.executionData.execution.id}/report.md`);

    await monacoApi.editorService.openEditor({
        resource: reportUri,
        options: {
            preserveFocus: temporary
        }
    });
    if (!temporary && activeTab.value != undefined) {
        activeTab.value.temporary = false;
    }

    emit("select", props.executionData);
}

function handleCancel() {
    emit("cancel", props.executionData.execution.id);
}

async function handleDownloadReport(): Promise<void> {
    const reportUri = Uri.file(`/${project.value!.id}/executions/${props.executionData.execution.id}/report.md`);
    const result = await monacoApi.fileService.readFile(reportUri);
    const content = result.value.toString();
    const blob = new Blob([content], { type: "application/octet-stream" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "report.md";
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}

function handleDelete() {
    emit("delete", props.executionData);
}

const isExpanded = computed(() => {
    return treeContext?.expandedItems.value.has(props.executionData) ?? false;
});

watch(
    isExpanded,
    async (expanded) => {
        if (
            expanded &&
            isCompletedSuccessfully.value &&
            props.executionData.fileTree == undefined &&
            !props.executionData.isLoadingTree
        ) {
            await workbenchState.loadExecutionFileTree(props.executionData.execution.id);
        }
    },
    { immediate: true }
);
</script>
