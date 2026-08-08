<template>
    <div
        ref="treeRef"
        tabindex="-1"
        :class="cn('flex flex-col gap-1 overflow-x-auto w-full group/tree', props.class)"
        @keydown="handleKeydown"
        @dragover="handleDragOver"
        @drop="handleDrop"
    >
        <slot />
    </div>
</template>

<script setup lang="ts">
import { computed, provide, useTemplateRef, type HTMLAttributes } from "vue";
import { cn } from "@/lib/utils";
import { treeContextKey, type TreeItem, type DragAndDropCallbacks } from "./util";

const props = defineProps<{
    class?: HTMLAttributes["class"];
    activeElement?: any;
    enableDragAndDrop?: boolean;
    dragAndDropCallbacks?: DragAndDropCallbacks;
    expandedItems: Set<TreeItem>;
}>();

const treeRef = useTemplateRef("treeRef");

const treeContext = {
    activeItem: computed(() => props.activeElement),
    dragAndDrop: computed(() => ({
        enabled: props.enableDragAndDrop ?? false,
        callbacks: props.dragAndDropCallbacks
    })),
    expandedItems: computed(() => props.expandedItems)
};

provide(treeContextKey, treeContext);

function handleKeydown(event: KeyboardEvent) {
    if (event.key !== "ArrowUp" && event.key !== "ArrowDown") {
        return;
    }

    event.preventDefault();

    if (!treeRef.value) {
        return;
    }

    const treeButtons = Array.from(treeRef.value.querySelectorAll('[data-tree="button"]')) as HTMLElement[];

    if (treeButtons.length === 0) {
        return;
    }
    let currentButton = treeButtons.find((button) => button === document.activeElement) as HTMLElement;

    if (!currentButton) {
        currentButton = treeRef.value.querySelector('[data-active="true"][data-tree="button"]') as HTMLElement;
    }

    if (!currentButton) {
        treeButtons[0]?.focus();
        return;
    }

    const currentIndex = treeButtons.indexOf(currentButton);

    if (currentIndex === -1) {
        return;
    }

    let nextIndex: number;

    if (event.key === "ArrowDown") {
        nextIndex = currentIndex + 1;
        if (nextIndex >= treeButtons.length) {
            nextIndex = 0;
        }
    } else {
        nextIndex = currentIndex - 1;
        if (nextIndex < 0) {
            nextIndex = treeButtons.length - 1;
        }
    }

    const nextButton = treeButtons[nextIndex];
    if (nextButton) {
        nextButton.focus();
    }
}

function handleDragOver(event: DragEvent) {
    if (!treeContext.dragAndDrop.value.enabled) {
        return;
    }

    event.preventDefault();
}

function handleDrop(event: DragEvent) {
    if (!treeContext.dragAndDrop.value.enabled) {
        return;
    }

    event.preventDefault();
    event.stopPropagation();

    if (event.dataTransfer != undefined && event.dataTransfer.files.length > 0) {
        treeContext.dragAndDrop.value.callbacks?.onFilesDropped?.(event.dataTransfer.files, undefined, event);
        return;
    }

    const draggedItemData = event.dataTransfer?.getData("application/json");
    if (!draggedItemData) {
        return;
    }

    const draggedItemInfo = JSON.parse(draggedItemData);

    treeContext.dragAndDrop.value.callbacks?.onTreeDrop?.(draggedItemInfo.id as string, event);
}
</script>
