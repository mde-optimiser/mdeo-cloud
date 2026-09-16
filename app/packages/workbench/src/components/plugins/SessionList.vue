<template>
    <div v-if="entries.length > 0" class="mt-2">
        <div class="text-sm font-medium text-foreground">Sessions</div>
        <div v-for="entry in entries" :key="entry.name" class="text-sm text-muted-foreground mt-1">
            <span class="font-mono">{{ address }}/{{ entry.name }}</span>
            &mdash; {{ entry.type.protocol }} v{{ entry.type.versions.join(", ") }}
            <div v-if="entry.type.description" class="text-xs">{{ entry.type.description }}</div>
        </div>
    </div>
</template>

<script setup lang="ts">
import type { SessionTypes } from "@mdeo/plugin";
import { computed } from "vue";

const props = defineProps<{
    /**
     * The target address these sessions belong to, e.g. `lang:script`.
     */
    address: string;
    /**
     * The declared session types, keyed by session name.
     */
    sessions?: SessionTypes;
}>();

/**
 * The declared sessions as a list, so the template can iterate them in a stable order.
 */
const entries = computed(() =>
    Object.entries(props.sessions ?? {})
        .map(([name, type]) => ({ name, type }))
        .sort((left, right) => left.name.localeCompare(right.name))
);
</script>
