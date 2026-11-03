<template>
    <Dialog :open="open" @update:open="(value) => emit('update:open', value)">
        <DialogContent class="sm:max-w-4xl w-full h-125 flex flex-col p-0">
            <DialogHeader class="p-6 pb-0">
                <DialogTitle>Manage Plugins</DialogTitle>
                <DialogDescription>Add or remove plugins from the project.</DialogDescription>
            </DialogHeader>
            <div class="flex gap-6 p-6 pt-4 min-h-0 flex-1">
                <PluginList
                    :plugins="allPlugins"
                    :selected-plugin-id="selectedPluginId"
                    empty-message="No plugins available."
                    @select="handleSelectPlugin"
                    class="w-80"
                >
                    <template #plugin-indicator="{ plugin }">
                        <Check v-if="isPluginInstalled(plugin)" class="size-4 ml-auto" />
                    </template>
                </PluginList>
                <PluginDetailsContainer :plugin="selectedPlugin" class="flex-1">
                    <template #content>
                        <PluginDetails v-if="selectedPlugin != undefined" :plugin="selectedPlugin">
                            <template #actions>
                                <Button
                                    v-if="!isPluginInstalled(selectedPlugin)"
                                    :disabled="isProcessing"
                                    @click="handleAddPlugin()"
                                >
                                    <Plus class="size-4 mr-2" />
                                    Add to Project
                                </Button>
                                <Button
                                    v-else
                                    variant="destructive"
                                    :disabled="isProcessing"
                                    @click="openRemoveDialog()"
                                >
                                    <Trash2 class="size-4 mr-2" />
                                    Remove from Project
                                </Button>
                            </template>
                        </PluginDetails>
                    </template>
                </PluginDetailsContainer>
            </div>
        </DialogContent>
    </Dialog>

    <AlertDialog v-model:open="isRemoveDialogOpen">
        <AlertDialogContent>
            <AlertDialogHeader>
                <AlertDialogTitle>Remove Plugin</AlertDialogTitle>
                <AlertDialogDescription>
                    Are you sure you want to remove "{{ selectedPlugin?.name }}" from this project?
                </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
                <AlertDialogCancel>Cancel</AlertDialogCancel>
                <AlertDialogAction @click="handleRemovePlugin">Remove</AlertDialogAction>
            </AlertDialogFooter>
        </AlertDialogContent>
    </AlertDialog>
</template>
<script setup lang="ts">
import { ref, shallowRef, computed, inject, watch } from "vue";
import { Plus, Trash2, Check } from "@lucide/vue";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
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
import PluginList from "@/components/plugins/PluginList.vue";
import PluginDetailsContainer from "@/components/plugins/PluginDetailsContainer.vue";
import PluginDetails from "@/components/plugins/PluginDetails.vue";
import { workbenchStateKey } from "@/components/workbench/util";
import type { Plugin } from "@mdeo/plugin";
import { resolvePlugin } from "@/data/plugin/resolvePlugin";
import { showApiError } from "@/lib/notifications";

const props = defineProps<{
    open: boolean;
    projectId: string;
    selectedPluginIdProp?: string;
}>();

const emit = defineEmits<{
    "update:open": [value: boolean];
}>();

const { backendApi, plugins: installedPlugins } = inject(workbenchStateKey)!;

const selectedPluginId = ref<string | null>(null);
const isProcessing = ref(false);
const isRemoveDialogOpen = ref(false);
const allPlugins = shallowRef<Plugin[]>([]);

const selectedPlugin = computed(() => {
    if (selectedPluginId.value == undefined) {
        return undefined;
    }
    return allPlugins.value.find((p) => p.id === selectedPluginId.value) ?? undefined;
});

function isPluginInstalled(plugin: Plugin): boolean {
    return installedPlugins.value.has(plugin.id);
}

function handleSelectPlugin(pluginId: string) {
    selectedPluginId.value = pluginId;
}

function openRemoveDialog() {
    isRemoveDialogOpen.value = true;
}

async function loadPlugins() {
    const allPluginsResult = await backendApi.plugins.getAll();

    if (allPluginsResult.success) {
        allPlugins.value = allPluginsResult.value.sort((a, b) => {
            return a.name.localeCompare(b.name);
        });
    } else {
        showApiError("load plugins", allPluginsResult.error.message);
    }
}

async function handleAddPlugin() {
    if (!selectedPlugin.value) {
        return;
    }

    isProcessing.value = true;
    try {
        const result = await backendApi.plugins.addToProject(props.projectId, selectedPlugin.value.id);
        if (result.success) {
            installedPlugins.value.set(selectedPlugin.value.id, await resolvePlugin(result.value));
        } else {
            showApiError("add plugin to project", result.error.message);
        }
    } finally {
        isProcessing.value = false;
    }
}

async function handleRemovePlugin() {
    if (!selectedPlugin.value) {
        return;
    }

    isProcessing.value = true;
    try {
        const result = await backendApi.plugins.removeFromProject(props.projectId, selectedPlugin.value.id);
        if (result.success) {
            installedPlugins.value.delete(selectedPlugin.value.id);
        } else {
            showApiError("remove plugin from project", result.error.message);
        }
    } finally {
        isProcessing.value = false;
    }
}

watch(
    () => props.open,
    async (isOpen) => {
        if (isOpen) {
            if (props.selectedPluginIdProp) {
                selectedPluginId.value = props.selectedPluginIdProp;
            }
            await loadPlugins();
        }
    }
);
</script>
