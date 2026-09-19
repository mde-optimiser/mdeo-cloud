<template>
    <div class="space-y-3">
        <div class="flex items-start justify-between gap-4">
            <h2 class="text-2xl font-semibold text-foreground flex-1 truncate">
                {{ plugin.name }}
            </h2>
            <slot name="actions"></slot>
        </div>
        <p class="text-sm text-muted-foreground">
            {{ plugin.description || "No description provided." }}
        </p>
        <a
            class="inline-flex items-center gap-2 text-sm font-medium text-primary break-all"
            :href="plugin.url"
            target="_blank"
            rel="noreferrer noopener"
        >
            <Link class="size-4" />
            {{ plugin.url }}
        </a>

        <Separator />

        <div v-if="visibleLanguagePlugins.length > 0" class="mt-6">
            <h3 class="text-lg font-semibold text-foreground mb-3">Language Plugins</h3>
            <div class="space-y-2">
                <div
                    v-for="langPlugin in visibleLanguagePlugins"
                    :key="langPlugin.id"
                    class="p-3 rounded-md border border-border bg-card"
                >
                    <div class="flex items-start gap-3">
                        <Icon
                            :iconNode="langPlugin.icon"
                            name="LanguagePluginIcon"
                            class="w-5 h-5 text-muted-foreground"
                        />
                        <div class="flex-1 min-w-0">
                            <div class="font-medium text-foreground">
                                {{ langPlugin.name }}
                                <span class="text-muted-foreground font-normal">({{ langPlugin.id }})</span>
                            </div>
                        </div>
                    </div>
                    <div class="text-sm text-muted-foreground mt-2">
                        Extension: <span class="font-mono">{{ langPlugin.extension }}</span>
                    </div>
                    <div class="text-sm text-muted-foreground mt-1">
                        Graphical Editor: {{ langPlugin.graphicalEditorPlugin ? "Supported" : "Not supported" }}
                    </div>
                    <div class="text-sm text-muted-foreground mt-1">
                        Textual Editor: {{ langPlugin.textualEditorPlugin ? "Supported" : "Not supported" }}
                    </div>
                </div>
            </div>
        </div>

        <div v-if="plugin.contributionPlugins && plugin.contributionPlugins.length > 0" class="mt-6">
            <h3 class="text-lg font-semibold text-foreground mb-3">Language Contribution Plugins</h3>
            <div class="space-y-2">
                <div
                    v-for="contribPlugin in plugin.contributionPlugins"
                    :key="contribPlugin.languageId"
                    class="p-3 rounded-md border border-border bg-card"
                >
                    <div class="font-medium text-foreground">
                        Contributes to: <span class="font-mono">{{ contribPlugin.languageId }}</span>
                    </div>
                    <div class="text-sm text-muted-foreground mt-1">
                        {{ contribPlugin.description || "No description provided." }}
                    </div>
                </div>
            </div>
        </div>
    </div>
</template>

<script setup lang="ts">
import type { Plugin } from "@mdeo/plugin";
import { Link, Icon } from "@lucide/vue";
import { Separator } from "@/components/ui/separator";
import { computed } from "vue";

const props = defineProps<{
    plugin: Plugin;
}>();

const visibleLanguagePlugins = computed(() => {
    return props.plugin.languagePlugins?.filter((langPlugin) => langPlugin.extension != null) ?? [];
});
</script>
