<template>
    <div class="flex flex-col h-full">
        <SidebarPanelHeader label="Projects">
            <template #actions>
                <Button v-if="project != undefined" variant="ghost" size="icon" class="h-8 w-8" @click="handleClose">
                    <X class="size-4" />
                </Button>
            </template>
        </SidebarPanelHeader>
        <div class="px-3 pb-2">
            <Button v-if="canCreateProject" @click="openNewProjectDialog" class="w-full mb-2">
                <Plus class="size-4 mr-2" />New Project
            </Button>
            <Input v-model="searchText" placeholder="Search projects..." />
        </div>
        <ScrollArea class="flex-1 min-h-0 w-full">
            <Tree class="flex-1 w-full p-2" :active-element="project" :expanded-items="expandedItems">
                <TreeItem
                    v-for="project in filteredProjects"
                    :key="project.id"
                    :data="project"
                    :is-folder="false"
                    :has-children="false"
                    @click="handleSelectProject(project)"
                >
                    <template #content>
                        <Folder class="size-4 mr-2" />
                        <span>{{ project.name }}</span>
                    </template>
                </TreeItem>
            </Tree>
        </ScrollArea>

        <Dialog v-model:open="isNewProjectDialogOpen">
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>Create New Project</DialogTitle>
                    <DialogDescription> Enter a name for your new project. </DialogDescription>
                </DialogHeader>
                <div class="py-4 space-y-3">
                    <Input v-model="newProjectName" placeholder="Project name" @keydown.enter="handleCreateProject" />
                    <div
                        class="border-2 border-dashed rounded-md p-4 text-sm transition-colors"
                        :class="isDragging ? 'border-primary bg-primary/5' : 'border-muted-foreground/25'"
                        @dragover.prevent="isDragging = true"
                        @dragenter.prevent="isDragging = true"
                        @dragleave.prevent="isDragging = false"
                        @drop.prevent="handleDrop"
                    >
                        <div v-if="importFile" class="flex items-center justify-between gap-2">
                            <span class="flex items-center gap-2 truncate">
                                <FileArchive class="size-4 shrink-0" />
                                <span class="truncate">{{ importFile.name }}</span>
                            </span>
                            <Button variant="ghost" size="icon" class="h-6 w-6 shrink-0" @click="clearImportFile">
                                <X class="size-4" />
                            </Button>
                        </div>
                        <label
                            v-else
                            class="flex cursor-pointer flex-col items-center gap-1 text-center text-muted-foreground"
                        >
                            <Upload class="size-5" />
                            <span>Drop a .zip here or click to import</span>
                            <input type="file" accept=".zip" class="hidden" @change="handleFileSelect" />
                        </label>
                    </div>
                </div>
                <DialogFooter>
                    <Button variant="outline" @click="isNewProjectDialogOpen = false"> Cancel </Button>
                    <Button @click="handleCreateProject" :disabled="!newProjectName.trim()"> Create </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    </div>
</template>

<script setup lang="ts">
import { ref, computed, inject } from "vue";
import Tree from "@/components/tree/Tree.vue";
import TreeItem from "@/components/tree/TreeItem.vue";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import ScrollArea from "@/components/ui/scroll-area/ScrollArea.vue";
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle
} from "@/components/ui/dialog";
import SidebarPanelHeader from "@/components/sidebar/SidebarPanelHeader.vue";
import type { Project } from "@/data/project/project";
import { Folder, Plus, X, Upload, FileArchive } from "lucide-vue-next";
import { authStateKey, workbenchStateKey } from "../workbench/util";
import { extractZip, type ImportedFile } from "@/lib/zip";
import { showApiError } from "@/lib/notifications";

const props = defineProps<{
    projects: Project[];
}>();

const emit = defineEmits<{
    createProject: [name: string, importFiles?: ImportedFile[]];
    close: [];
}>();

const { project } = inject(workbenchStateKey)!;
const authState = inject(authStateKey)!;

const searchText = ref("");
const expandedItems = ref<Set<any>>(new Set());
const isNewProjectDialogOpen = ref(false);
const newProjectName = ref("");
const importFile = ref<File | null>(null);
const isDragging = ref(false);

const filteredProjects = computed(() => {
    if (!searchText.value.trim()) {
        return props.projects;
    }
    const search = searchText.value.toLowerCase();
    return props.projects.filter((p) => p.name.toLowerCase().includes(search));
});

const canCreateProject = computed(() => {
    const user = authState.user.value;
    return (user?.isAdmin ?? false) || (user?.canCreateProject ?? false);
});

function handleSelectProject(selectedProject: Project) {
    project.value = selectedProject;
}

function openNewProjectDialog() {
    newProjectName.value = "";
    importFile.value = null;
    isDragging.value = false;
    isNewProjectDialogOpen.value = true;
}

function handleFileSelect(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) {
        importFile.value = file;
    }
    input.value = "";
}

function handleDrop(event: DragEvent) {
    isDragging.value = false;
    const file = event.dataTransfer?.files?.[0];
    if (file && file.name.toLowerCase().endsWith(".zip")) {
        importFile.value = file;
    }
}

function clearImportFile() {
    importFile.value = null;
}

async function handleCreateProject() {
    const name = newProjectName.value.trim();
    if (!name) {
        return;
    }

    let importFiles: ImportedFile[] | undefined;
    if (importFile.value) {
        try {
            const buffer = new Uint8Array(await importFile.value.arrayBuffer());
            importFiles = extractZip(buffer);
        } catch (error) {
            showApiError("import project archive", error instanceof Error ? error.message : String(error));
            return;
        }
    }

    emit("createProject", name, importFiles);
    isNewProjectDialogOpen.value = false;
    newProjectName.value = "";
    importFile.value = null;
}

function handleClose() {
    emit("close");
}
</script>
