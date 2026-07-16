<template>
    <TreeButton
        v-if="!isFolder"
        v-bind="$attrs"
        :data="data"
        :mode="mode"
        @dragenter="handleDragEnter"
        @dragleave="handleDragLeave"
        @dragover="handleDragOver"
        @drop="handleDrop"
    >
        <slot name="content" />
    </TreeButton>

    <li
        v-else
        data-slot="tree-item"
        data-tree="item"
        :class="cn('group/tree-item relative')"
        @dragenter="handleDragEnter"
        @dragleave="handleDragLeave"
        @dragover="handleDragOver"
        @drop="handleDrop"
    >
        <Collapsible
            v-model:open="isOpen"
            class="group/collapsible [&[data-state=open]>button>svg:first-child]:rotate-90"
        >
            <CollapsibleTrigger as-child>
                <TreeButton v-bind="$attrs" :data="data" :mode="mode">
                    <ChevronRight class="transition-transform" />
                    <slot name="content" />
                </TreeButton>
            </CollapsibleTrigger>
            <CollapsibleContent v-if="hasChildren">
                <ul
                    data-slot="tree-sub"
                    data-tree="sub"
                    :class="
                        cn(
                            'border-sidebar-border ml-3.5 mr-px flex min-w-0 translate-x-px flex-col gap-1 border-l pl-2.5 py-0.5',
                            'group-data-[collapsible=icon]:hidden'
                        )
                    "
                    @click.stop
                    @dblclick.stop
                >
                    <slot name="items" />
                </ul>
            </CollapsibleContent>
        </Collapsible>
    </li>
</template>

<script setup lang="ts" generic="T extends TreeItem">
import { ChevronRight } from "lucide-vue-next";
import { cn } from "@/lib/utils";

import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import TreeButton, { type TreeButtonProps } from "./TreeButton.vue";
import { treeContextKey, type TreeItem } from "./util";
import { computed, inject, ref } from "vue";

const props = defineProps<
    {
        data: T;
        isFolder: boolean;
        hasChildren: boolean;
    } & TreeButtonProps
>();

const hoverTimeout = ref<number | null>(null);

const treeContext = inject(treeContextKey);

const isOpen = computed({
    get() {
        return treeContext?.expandedItems.value.has(props.data) ?? false;
    },
    set(value: boolean) {
        if (!treeContext) {
            return;
        }
        if (value) {
            treeContext.expandedItems.value.add(props.data);
        } else {
            treeContext.expandedItems.value.delete(props.data);
        }
    }
});

function handleDragEnter(event: DragEvent) {
    if (!treeContext?.dragAndDrop.value.enabled || !props.isFolder) {
        return;
    }

    event.preventDefault();

    if (hoverTimeout.value) {
        clearTimeout(hoverTimeout.value);
    }
    hoverTimeout.value = setTimeout(() => {
        if (!isOpen.value) {
            isOpen.value = true;
        }
    }, 1000);
}

function handleDragLeave(event: DragEvent) {
    if (!treeContext?.dragAndDrop.value.enabled) {
        return;
    }

    if (hoverTimeout.value) {
        clearTimeout(hoverTimeout.value);
        hoverTimeout.value = null;
    }
}

function handleDragOver(event: DragEvent) {
    if (!treeContext?.dragAndDrop.value.enabled) {
        return;
    }

    event.preventDefault();
}

function handleDrop(event: DragEvent) {
    if (!treeContext?.dragAndDrop.value.enabled) {
        return;
    }

    event.preventDefault();

    if (hoverTimeout.value) {
        clearTimeout(hoverTimeout.value);
        hoverTimeout.value = null;
    }

    if (event.dataTransfer != undefined && event.dataTransfer.files.length > 0) {
        if (props.isFolder) {
            event.stopPropagation();
            treeContext.dragAndDrop.value.callbacks?.onFilesDropped?.(event.dataTransfer.files, props.data, event);
        }
        return;
    }

    const draggedItemData = event.dataTransfer?.getData("application/json");
    if (!draggedItemData) {
        return;
    }

    const draggedItemInfo = JSON.parse(draggedItemData);

    const canDrop =
        treeContext.dragAndDrop.value.callbacks?.canDrop?.(draggedItemInfo.id as string, props.data) ?? true;
    if (!canDrop) {
        return;
    }

    if (props.isFolder) {
        event.stopPropagation();
        treeContext.dragAndDrop.value.callbacks?.onDrop?.(draggedItemInfo.id as string, props.data, event);
    }
}
</script>
