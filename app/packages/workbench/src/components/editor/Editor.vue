<template>
    <div class="w-full h-full">
        <ResizablePanelGroup v-show="showFileEditor" direction="horizontal">
            <ResizablePanel v-show="hasTextualEditor">
                <div ref="editorElement" class="h-full w-full"></div>
            </ResizablePanel>
            <ResizableHandle v-show="hasTextualEditor && hasGraphicalEditor" />
            <ResizablePanel v-show="hasGraphicalEditor">
                <GraphicalEditor
                    v-for="state in graphicalEditorStates"
                    :key="state.tab.fileUri.toString()"
                    v-show="state.tab === activeTab"
                    :tab="state.tab"
                    :language-plugin="state.languagePlugin.value!"
                    :editable="!state.languagePlugin.value!.isGenerated"
                    :is-active="state.tab === activeTab"
                />
            </ResizablePanel>
        </ResizablePanelGroup>
        <div v-show="showMarkdownViewer" class="h-full w-full">
            <MarkdownRenderer
                v-for="state in markdownEditorStates"
                :key="state.tab.fileUri.toString()"
                v-show="state.tab === activeTab"
                :uri="state.tab.fileUri"
                :is-active="state.tab === activeTab"
            />
        </div>
        <div v-show="showNoLanguageSupport" class="h-full flex items-center justify-center p-8">
            <div class="flex flex-col items-center gap-4 max-w-md text-center">
                <AlertCircle class="w-12 h-12 text-muted-foreground" />
                <div class="space-y-2">
                    <h3 class="text-lg font-semibold text-foreground">No Language Support Available</h3>
                    <p class="text-sm text-muted-foreground">
                        No language support for the file extension
                        <span class="font-mono font-medium">{{
                            activeTab && getFileExtension(activeTab.fileUri.path)
                        }}</span>
                        is currently available.
                    </p>
                </div>
                <Button @click="openManagePlugins">
                    <Settings class="size-4 mr-2" />
                    Manage Plugins
                </Button>
            </div>
        </div>

        <ManagePluginsDialog
            v-if="project != undefined"
            v-model:open="isManagePluginsDialogOpen"
            :project-id="project.id"
        />
    </div>
</template>
<script setup lang="ts">
import { computed, inject, nextTick, onMounted, onUnmounted, shallowRef, useTemplateRef, watch } from "vue";
import { ResizableHandle, ResizablePanel, ResizablePanelGroup } from "../ui/resizable";
import { useResizeObserver, watchArray } from "@vueuse/core";
import * as monacoType from "monaco-editor";
import { workbenchStateKey } from "../workbench/util";
import type { EditorTab } from "@/data/tab/editorTab";
import { EditorState } from "./editorState";
import GraphicalEditor from "./GraphicalEditor.vue";
import MarkdownRenderer from "./MarkdownRenderer.vue";
import { AlertCircle, Settings } from "lucide-vue-next";
import { Button } from "@/components/ui/button";
import ManagePluginsDialog from "../projects/ManagePluginsDialog.vue";
import type { Uri } from "vscode";
import { getFileExtension } from "@/data/filesystem/util";

const { tabs, activeTab, languagePluginByExtension, monacoApi, project } = inject(workbenchStateKey)!;
const editorElement = useTemplateRef("editorElement");
const editor = shallowRef<monacoType.editor.IStandaloneCodeEditor>();

const editorStates = new Map<EditorTab, EditorState>();

watchArray(
    tabs,
    (_new, _old, _added, removed) => {
        for (const tab of removed) {
            const state = editorStates.get(tab);
            if (state != undefined) {
                state.dispose();
                editorStates.delete(tab);
            }
        }
    },
    {
        deep: true
    }
);

function getOrCreateEditorState(tab: EditorTab): EditorState {
    let state = editorStates.get(tab);
    if (state == undefined) {
        state = new EditorState(tab, languagePluginByExtension, editor);
        editorStates.set(tab, state);
    }
    return state;
}

const activeTabEditorState = computed(() => {
    const tab = activeTab.value;
    if (tab == undefined) {
        return undefined;
    }
    return getOrCreateEditorState(tab);
});

const graphicalEditorStates = computed(() => {
    return tabs.value.map((tab) => getOrCreateEditorState(tab)).filter((state) => state.hasGraphicalEditor.value);
});

const markdownEditorStates = computed(() => {
    return tabs.value.map((tab) => getOrCreateEditorState(tab)).filter((state) => state.hasMarkdownViewer.value);
});

const languagePlugin = computed(() => {
    const tab = activeTab.value;
    if (tab == undefined) {
        return undefined;
    }
    return languagePluginByExtension.value.get(getFileExtension(tab.fileUri.path));
});

const hasGraphicalEditor = computed(() => {
    return activeTabEditorState.value?.hasGraphicalEditor.value ?? false;
});

const hasTextualEditor = computed(() => {
    return activeTabEditorState.value?.hasTextualEditor.value ?? false;
});

const showFileEditor = computed(() => {
    return hasGraphicalEditor.value || hasTextualEditor.value;
});

const showMarkdownViewer = computed(() => {
    return activeTabEditorState.value?.hasMarkdownViewer.value ?? false;
});

const showNoLanguageSupport = computed(() => {
    return !showFileEditor.value && !showMarkdownViewer.value;
});

watch(
    activeTab,
    (newTab, oldTab) => {
        const oldState = oldTab ? editorStates.get(oldTab) : undefined;
        if (oldState != undefined) {
            oldState.deactivate();
        }
        const newState = newTab ? getOrCreateEditorState(newTab) : undefined;
        if (newState != undefined) {
            newState.activate();
        }
    },
    {
        immediate: true,
        deep: true
    }
);

useResizeObserver(editorElement, () => {
    const monacoEditor = editor.value;
    if (monacoEditor != undefined) {
        monacoEditor.layout();
    }
});

function openTab(file: Uri, temporary: boolean) {
    const existingTab = tabs.value.find((tab) => tab.fileUri.toString() === file.toString());

    if (existingTab) {
        activeTab.value = existingTab;
        if (!temporary && existingTab.temporary) {
            existingTab.temporary = false;
        }
    } else {
        const newTab: EditorTab = {
            fileUri: file,
            temporary: temporary
        };
        tabs.value.push(newTab);
        activeTab.value = newTab;
    }
}

onMounted(() => {
    const monacoEditor = monacoApi.monaco.editor.create(editorElement.value!);
    monacoEditor.updateOptions({
        hover: {
            above: false
        },
        glyphMargin: false,
        fixedOverflowWidgets: true,
        automaticLayout: false
    });
    monacoEditor.layout();
    editor.value = monacoEditor;
    monacoApi.openEditorFunc = async (createModelReference, options) => {
        const targetUri = createModelReference.object.textEditorModel.uri;
        openTab(targetUri, options?.pinned !== true);
        const sel = (options as { selection?: monacoType.IRange } | undefined)?.selection;
        if (sel != undefined) {
            const tab = activeTab.value;
            if (tab != null) {
                getOrCreateEditorState(tab).setPendingSelection(sel);
            }
            await nextTick();
            if (editor.value?.getModel()?.uri.toString() === targetUri.toString()) {
                editor.value.setSelection(sel);
                editor.value.revealRangeNearTopIfOutsideViewport(sel);
            }
        }
        return undefined;
    };
});

onUnmounted(() => {
    editor.value?.dispose();
    editor.value = undefined;
});

const isManagePluginsDialogOpen = shallowRef(false);

function openManagePlugins() {
    if (project.value) {
        isManagePluginsDialogOpen.value = true;
    }
}
</script>
