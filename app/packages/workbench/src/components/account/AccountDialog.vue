<template>
    <Dialog v-model:open="open">
        <DialogContent class="sm:max-w-lg w-full h-112 flex flex-col">
            <DialogHeader class="sr-only">
                <DialogTitle>Account</DialogTitle>
            </DialogHeader>

            <Tabs v-model="activeTab" class="flex-1 flex flex-col min-h-0">
                <div class="flex items-center gap-3 pb-4">
                    <div
                        class="flex size-10 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary"
                    >
                        <UserRound class="size-5" />
                    </div>
                    <div class="min-w-0 flex-1">
                        <p class="truncate text-sm font-semibold text-foreground">{{ username }}</p>
                        <p class="text-xs text-muted-foreground">Signed in to MDEO</p>
                    </div>
                    <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        class="mr-6 text-muted-foreground hover:text-destructive"
                        :disabled="isLoggingOut"
                        @click="handleLogout"
                    >
                        <LogOut class="size-4" />
                        <span>{{ isLoggingOut ? "Logging out…" : "Log out" }}</span>
                    </Button>
                </div>

                <TabsList class="inline-flex h-9 w-fit items-center gap-1 rounded-full bg-muted/30 p-1">
                    <TabsTrigger value="profile" class="px-4 py-1.5 rounded-full gap-2">
                        <Lock class="size-4" />
                        Password
                    </TabsTrigger>
                    <TabsTrigger value="tokens" class="px-4 py-1.5 rounded-full gap-2">
                        <KeyRound class="size-4" />
                        Access tokens
                    </TabsTrigger>
                    <TabsTrigger value="ssh" class="px-4 py-1.5 rounded-full gap-2">
                        <Terminal class="size-4" />
                        SSH keys
                    </TabsTrigger>
                </TabsList>

                <!-- Password -->
                <TabsContent value="profile" class="flex-1 min-h-0">
                    <ScrollArea class="h-full pr-3">
                        <div class="space-y-4">
                            <div class="rounded-xl border border-border/70 p-4">
                                <div class="flex items-center gap-3">
                                    <div
                                        class="flex size-9 shrink-0 items-center justify-center rounded-full bg-muted/50 text-muted-foreground"
                                    >
                                        <Lock class="size-4" />
                                    </div>
                                    <div class="min-w-0 flex-1">
                                        <p class="text-sm font-medium text-foreground">Password</p>
                                        <p class="text-xs text-muted-foreground">
                                            Used to sign in to the workbench and to authorize git access.
                                        </p>
                                    </div>
                                </div>
                            </div>

                            <Collapsible v-model:open="isPasswordFormOpen">
                                <CollapsibleTrigger asChild>
                                    <Button type="button" variant="outline" class="w-full justify-between">
                                        <span>Change password</span>
                                        <component :is="isPasswordFormOpen ? ChevronUp : ChevronDown" class="size-4" />
                                    </Button>
                                </CollapsibleTrigger>
                                <CollapsibleContent>
                                    <form class="mt-4 space-y-4" @submit.prevent="handlePasswordChange">
                                        <FieldGroup>
                                            <Field>
                                                <FieldLabel for="current-password">Current password</FieldLabel>
                                                <FieldContent>
                                                    <PasswordField
                                                        id="current-password"
                                                        v-model="currentPassword"
                                                        autocomplete="current-password"
                                                        placeholder="••••••••"
                                                    />
                                                </FieldContent>
                                            </Field>

                                            <Field>
                                                <FieldLabel for="new-password">New password</FieldLabel>
                                                <FieldContent>
                                                    <PasswordField
                                                        id="new-password"
                                                        v-model="newPassword"
                                                        autocomplete="new-password"
                                                    />
                                                </FieldContent>
                                            </Field>

                                            <Field>
                                                <FieldLabel for="confirm-password">Confirm new password</FieldLabel>
                                                <FieldContent>
                                                    <PasswordField
                                                        id="confirm-password"
                                                        v-model="confirmPassword"
                                                        autocomplete="new-password"
                                                        placeholder="Repeat new password"
                                                    />
                                                </FieldContent>
                                            </Field>

                                            <Field v-if="passwordError">
                                                <FieldError :errors="[passwordError]" />
                                            </Field>

                                            <Field v-if="passwordSuccess">
                                                <FieldContent>
                                                    <div
                                                        class="flex items-center gap-2 rounded-lg border border-emerald-400/40 bg-emerald-400/10 px-3 py-2 text-sm text-emerald-500"
                                                    >
                                                        <CircleCheck class="size-4 shrink-0" />
                                                        {{ passwordSuccess }}
                                                    </div>
                                                </FieldContent>
                                            </Field>

                                            <Field>
                                                <FieldContent>
                                                    <Button type="submit" class="w-fit" :disabled="isUpdatingPassword">
                                                        {{ isUpdatingPassword ? "Updating…" : "Update password" }}
                                                    </Button>
                                                </FieldContent>
                                            </Field>
                                        </FieldGroup>
                                    </form>
                                </CollapsibleContent>
                            </Collapsible>
                        </div>
                    </ScrollArea>
                </TabsContent>

                <!-- Access tokens -->
                <TabsContent value="tokens" class="flex-1 min-h-0">
                    <ScrollArea class="h-full pr-3">
                        <div class="space-y-4">
                            <p class="text-xs text-muted-foreground">
                                Use a token instead of your password when cloning or pushing over git. A token can be
                                revoked on its own, and can be limited to specific projects.
                            </p>

                            <div
                                v-if="createdToken"
                                class="space-y-3 rounded-xl border border-emerald-400/40 bg-emerald-400/5 p-4"
                            >
                                <div class="flex items-center gap-2 text-sm font-medium text-emerald-500">
                                    <CircleCheck class="size-4 shrink-0" />
                                    <span>Token created</span>
                                </div>
                                <p class="text-xs text-muted-foreground">Copy it now — it will not be shown again.</p>
                                <div class="flex items-center gap-2">
                                    <Input
                                        :model-value="createdToken.token"
                                        readonly
                                        class="flex-1 font-mono text-xs"
                                    />
                                    <Tooltip>
                                        <TooltipTrigger asChild>
                                            <Button
                                                variant="ghost"
                                                size="icon"
                                                class="size-8 shrink-0"
                                                :aria-label="isTokenCopied ? 'Copied' : 'Copy token'"
                                                @click="handleCopyToken"
                                            >
                                                <Check v-if="isTokenCopied" class="size-4" />
                                                <Copy v-else class="size-4" />
                                            </Button>
                                        </TooltipTrigger>
                                        <TooltipContent side="top">
                                            {{ isTokenCopied ? "Copied" : "Copy token" }}
                                        </TooltipContent>
                                    </Tooltip>
                                </div>
                                <Button type="button" variant="ghost" size="sm" @click="createdToken = undefined"
                                    >Done</Button
                                >
                            </div>

                            <ul v-if="tokens.length > 0" class="space-y-2">
                                <li
                                    v-for="token in tokens"
                                    :key="token.id"
                                    class="group rounded-xl border border-border/70 p-3 transition-colors hover:border-border hover:bg-muted/30"
                                >
                                    <div class="flex items-start justify-between gap-3">
                                        <div class="min-w-0 space-y-2">
                                            <div class="flex items-center gap-2">
                                                <p class="truncate text-sm font-medium text-foreground">
                                                    {{ token.name }}
                                                </p>
                                                <span
                                                    v-if="isExpired(token.expiresAt)"
                                                    class="shrink-0 rounded-full bg-destructive/10 px-2 py-0.5 text-[10px] font-medium text-destructive"
                                                >
                                                    Expired
                                                </span>
                                            </div>
                                            <p class="truncate font-mono text-xs text-muted-foreground">
                                                {{ token.tokenPrefix }}…
                                            </p>

                                            <div class="flex flex-wrap items-center gap-1.5">
                                                <span
                                                    v-if="!token.scoped"
                                                    class="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-[11px] text-muted-foreground"
                                                >
                                                    <Globe class="size-3" />
                                                    All projects
                                                </span>
                                                <!-- A scoped token whose projects have all been
                                                 deleted reaches nothing at all, which is very
                                                 much not the same as reaching everything. -->
                                                <span
                                                    v-else-if="token.projects.length === 0"
                                                    class="inline-flex items-center gap-1 rounded-full bg-destructive/10 px-2 py-0.5 text-[11px] text-destructive"
                                                >
                                                    <FolderGit2 class="size-3" />
                                                    No projects left
                                                </span>
                                                <span
                                                    v-for="scoped in token.projects.slice(0, 3)"
                                                    :key="scoped.id"
                                                    class="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2 py-0.5 text-[11px] text-primary"
                                                >
                                                    <FolderGit2 class="size-3" />
                                                    {{ scoped.name }}
                                                </span>
                                                <Tooltip v-if="token.projects.length > 3">
                                                    <TooltipTrigger asChild>
                                                        <span
                                                            class="cursor-default rounded-full bg-muted px-2 py-0.5 text-[11px] text-muted-foreground"
                                                        >
                                                            +{{ token.projects.length - 3 }} more
                                                        </span>
                                                    </TooltipTrigger>
                                                    <TooltipContent side="top" class="max-w-64">
                                                        {{ token.projects.map((p) => p.name).join(", ") }}
                                                    </TooltipContent>
                                                </Tooltip>
                                            </div>

                                            <div
                                                class="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-muted-foreground"
                                            >
                                                <span class="inline-flex items-center gap-1">
                                                    <Clock class="size-3" />
                                                    {{ lastUsedLabel(token.lastUsedAt) }}
                                                </span>
                                                <span>Created {{ formatRelative(token.createdAt) }}</span>
                                                <span v-if="token.expiresAt && !isExpired(token.expiresAt)">
                                                    Expires {{ formatRelative(token.expiresAt) }}
                                                </span>
                                            </div>
                                        </div>

                                        <Button
                                            type="button"
                                            variant="ghost"
                                            size="icon"
                                            class="size-8 shrink-0 text-destructive"
                                            aria-label="Revoke token"
                                            :disabled="revokingTokenId === token.id"
                                            @click="handleRevokeToken(token.id)"
                                        >
                                            <Trash2 class="size-4" />
                                        </Button>
                                    </div>
                                </li>
                            </ul>

                            <div
                                v-else-if="!createdToken"
                                class="flex flex-col items-center gap-2 rounded-xl border border-dashed border-border/70 py-8 text-center"
                            >
                                <KeyRound class="size-6 text-muted-foreground/60" />
                                <p class="text-sm text-muted-foreground">No access tokens yet</p>
                            </div>

                            <form
                                class="space-y-3 rounded-xl border border-border/70 p-3"
                                @submit.prevent="handleCreateToken"
                            >
                                <p class="text-xs font-medium text-foreground">New token</p>
                                <Input v-model="newTokenName" placeholder="Token name, e.g. this laptop" />

                                <div class="space-y-2">
                                    <p class="text-xs text-muted-foreground">Project access</p>
                                    <div class="inline-flex rounded-full bg-muted/40 p-1 text-xs">
                                        <button
                                            type="button"
                                            class="rounded-full px-3 py-1 transition-colors"
                                            :class="
                                                scopeMode === 'all'
                                                    ? 'bg-background font-medium text-foreground shadow-sm'
                                                    : 'text-muted-foreground hover:text-foreground'
                                            "
                                            @click="scopeMode = 'all'"
                                        >
                                            All projects
                                        </button>
                                        <button
                                            type="button"
                                            class="rounded-full px-3 py-1 transition-colors"
                                            :class="
                                                scopeMode === 'selected'
                                                    ? 'bg-background font-medium text-foreground shadow-sm'
                                                    : 'text-muted-foreground hover:text-foreground'
                                            "
                                            @click="handleSelectScopedMode"
                                        >
                                            Selected projects
                                        </button>
                                    </div>

                                    <div v-if="scopeMode === 'selected'" class="space-y-2">
                                        <p v-if="availableProjects.length === 0" class="text-xs text-muted-foreground">
                                            No projects available to scope to.
                                        </p>
                                        <template v-else>
                                            <!-- Selected projects are summarised rather than listed in
                                             full: a token scoped to fifty projects would otherwise
                                             push the create form off the dialog. -->
                                            <div
                                                v-if="selectedProjects.length > 0"
                                                class="flex flex-wrap items-center gap-1.5"
                                            >
                                                <span
                                                    v-for="chosen in selectedProjects.slice(0, 4)"
                                                    :key="chosen.id"
                                                    class="inline-flex items-center gap-1 rounded-full bg-primary/10 py-0.5 pl-2 pr-1 text-[11px] text-primary"
                                                >
                                                    {{ chosen.name }}
                                                    <button
                                                        type="button"
                                                        class="rounded-full p-0.5 hover:bg-primary/20"
                                                        :aria-label="`Remove ${chosen.name}`"
                                                        @click="toggleProject(chosen.id)"
                                                    >
                                                        <X class="size-3" />
                                                    </button>
                                                </span>
                                                <span
                                                    v-if="selectedProjects.length > 4"
                                                    class="text-[11px] text-muted-foreground"
                                                >
                                                    +{{ selectedProjects.length - 4 }} more
                                                </span>
                                                <button
                                                    type="button"
                                                    class="text-[11px] text-muted-foreground underline-offset-2 hover:underline"
                                                    @click="selectedProjectIds = []"
                                                >
                                                    Clear
                                                </button>
                                            </div>

                                            <div class="relative">
                                                <Search
                                                    class="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground"
                                                />
                                                <Input
                                                    v-model="projectSearch"
                                                    placeholder="Search projects"
                                                    class="h-8 pl-8 text-xs"
                                                />
                                            </div>

                                            <!-- Scrolls rather than growing, so the list is the same
                                             size whether the user has three projects or three
                                             hundred; search is how you reach the rest. -->
                                            <ScrollArea class="h-40 rounded-lg border border-border/70">
                                                <p
                                                    v-if="filteredProjects.length === 0"
                                                    class="px-3 py-2 text-[11px] text-muted-foreground"
                                                >
                                                    No projects match “{{ projectSearch }}”.
                                                </p>
                                                <button
                                                    v-for="candidate in filteredProjects"
                                                    :key="candidate.id"
                                                    type="button"
                                                    class="flex w-full items-center gap-2 px-2.5 py-1.5 text-left text-xs transition-colors hover:bg-muted/50"
                                                    @click="toggleProject(candidate.id)"
                                                >
                                                    <span
                                                        class="flex size-4 shrink-0 items-center justify-center rounded border"
                                                        :class="
                                                            selectedProjectIds.includes(candidate.id)
                                                                ? 'border-primary bg-primary text-primary-foreground'
                                                                : 'border-border'
                                                        "
                                                    >
                                                        <Check
                                                            v-if="selectedProjectIds.includes(candidate.id)"
                                                            class="size-3"
                                                        />
                                                    </span>
                                                    <span class="truncate">{{ candidate.name }}</span>
                                                </button>
                                            </ScrollArea>
                                            <p class="text-[11px] text-muted-foreground">
                                                {{ selectedProjectIds.length }} of
                                                {{ availableProjects.length }} selected
                                            </p>
                                        </template>
                                        <p
                                            v-if="scopeWarning"
                                            class="flex items-center gap-1 text-[11px] text-amber-500"
                                        >
                                            <CircleAlert class="size-3 shrink-0" />
                                            {{ scopeWarning }}
                                        </p>
                                    </div>
                                </div>

                                <Button type="submit" :disabled="isCreatingToken || !canCreateToken">
                                    <Plus class="size-4" />
                                    {{ isCreatingToken ? "Creating…" : "Create token" }}
                                </Button>
                            </form>

                            <Field v-if="tokensError">
                                <FieldError :errors="[tokensError]" />
                            </Field>
                        </div>
                    </ScrollArea>
                </TabsContent>

                <!-- SSH keys -->
                <TabsContent value="ssh" class="flex-1 min-h-0">
                    <ScrollArea class="h-full pr-3">
                        <div class="space-y-4">
                            <p class="text-xs text-muted-foreground">
                                Register a public key to clone or push over git-over-SSH instead of HTTP.
                            </p>

                            <ul v-if="sshKeys.length > 0" class="space-y-2">
                                <li
                                    v-for="key in sshKeys"
                                    :key="key.id"
                                    class="rounded-xl border border-border/70 p-3 transition-colors hover:border-border hover:bg-muted/30"
                                >
                                    <div class="flex items-start justify-between gap-3">
                                        <div class="min-w-0 space-y-2">
                                            <p class="truncate text-sm font-medium text-foreground">{{ key.name }}</p>
                                            <p class="truncate font-mono text-xs text-muted-foreground">
                                                {{ key.fingerprint }}
                                            </p>
                                            <div
                                                class="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-muted-foreground"
                                            >
                                                <span class="inline-flex items-center gap-1">
                                                    <Clock class="size-3" />
                                                    {{ lastUsedLabel(key.lastUsedAt) }}
                                                </span>
                                                <span>Added {{ formatRelative(key.createdAt) }}</span>
                                            </div>
                                        </div>
                                        <Button
                                            type="button"
                                            variant="ghost"
                                            size="icon"
                                            class="size-8 shrink-0 text-destructive"
                                            aria-label="Remove key"
                                            :disabled="removingSshKeyId === key.id"
                                            @click="handleRemoveSshKey(key.id)"
                                        >
                                            <Trash2 class="size-4" />
                                        </Button>
                                    </div>
                                </li>
                            </ul>

                            <div
                                v-else
                                class="flex flex-col items-center gap-2 rounded-xl border border-dashed border-border/70 py-8 text-center"
                            >
                                <Terminal class="size-6 text-muted-foreground/60" />
                                <p class="text-sm text-muted-foreground">No SSH keys yet</p>
                            </div>

                            <form
                                class="space-y-3 rounded-xl border border-border/70 p-3"
                                @submit.prevent="handleAddSshKey"
                            >
                                <p class="text-xs font-medium text-foreground">New key</p>
                                <Input v-model="newSshKeyName" placeholder="Key name, e.g. this laptop" />
                                <textarea
                                    v-model="newSshKeyValue"
                                    placeholder="ssh-ed25519 AAAA... comment"
                                    rows="3"
                                    class="w-full rounded-lg border border-input bg-transparent px-3 py-2 font-mono text-xs shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                                />
                                <Button
                                    type="submit"
                                    :disabled="isAddingSshKey || !newSshKeyName.trim() || !newSshKeyValue.trim()"
                                >
                                    <Plus class="size-4" />
                                    {{ isAddingSshKey ? "Adding…" : "Add key" }}
                                </Button>
                            </form>

                            <Field v-if="sshKeysError">
                                <FieldError :errors="[sshKeysError]" />
                            </Field>
                        </div>
                    </ScrollArea>
                </TabsContent>
            </Tabs>
        </DialogContent>
    </Dialog>
</template>

<script setup lang="ts">
import { computed, inject, ref, watch } from "vue";
import {
    UserRound,
    LogOut,
    Copy,
    Check,
    Trash2,
    KeyRound,
    Terminal,
    Clock,
    FolderGit2,
    Plus,
    Globe,
    CircleAlert,
    CircleCheck,
    Lock,
    Search,
    X,
    ChevronDown,
    ChevronUp
} from "@lucide/vue";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Tooltip, TooltipTrigger, TooltipContent } from "@/components/ui/tooltip";
import PasswordField from "@/components/auth/PasswordField.vue";
import { Field, FieldContent, FieldError, FieldGroup, FieldLabel } from "@/components/ui/field";
import type { PersonalAccessTokenCreated, PersonalAccessTokenInfo, SshPublicKeyInfo } from "@/data/api/areas/authApi";
import type { Project } from "@/data/project/project";
import { authStateKey, workbenchStateKey } from "../workbench/util";

const open = defineModel<boolean>("open", { default: false });

const injectedAuthState = inject(authStateKey);
if (injectedAuthState == undefined) {
    throw new Error("AccountDialog requires an auth state");
}
const authState = injectedAuthState;

// Optional: the dialog is mounted inside the workbench today, but scoping a
// token is the only thing that needs the projects API, so a missing
// workbench state degrades to an unscoped-only token rather than throwing.
const workbenchState = inject(workbenchStateKey, undefined);

const activeTab = ref("profile");

const currentPassword = ref("");
const newPassword = ref("");
const confirmPassword = ref("");
const passwordError = ref<string>();
const passwordSuccess = ref<string>();
const isUpdatingPassword = ref(false);
const isLoggingOut = ref(false);
const isPasswordFormOpen = ref(false);

const tokens = ref<PersonalAccessTokenInfo[]>([]);
const tokensError = ref<string>();
const newTokenName = ref("");
const isCreatingToken = ref(false);
const createdToken = ref<PersonalAccessTokenCreated>();
const isTokenCopied = ref(false);
const revokingTokenId = ref<string>();

const scopeMode = ref<"all" | "selected">("all");
const selectedProjectIds = ref<string[]>([]);
const availableProjects = ref<Project[]>([]);
const projectSearch = ref("");

const sshKeys = ref<SshPublicKeyInfo[]>([]);
const sshKeysError = ref<string>();
const newSshKeyName = ref("");
const newSshKeyValue = ref("");
const isAddingSshKey = ref(false);
const removingSshKeyId = ref<string>();

const username = computed(() => authState.user.value?.username ?? "Unknown user");

const selectedProjects = computed(() =>
    availableProjects.value.filter((candidate) => selectedProjectIds.value.includes(candidate.id))
);

// Filtered in the client because the projects list is already fetched whole;
// if that ever stops being true this is the seam to move server-side.
const filteredProjects = computed(() => {
    const needle = projectSearch.value.trim().toLowerCase();
    if (!needle) {
        return availableProjects.value;
    }
    return availableProjects.value.filter((candidate) => candidate.name.toLowerCase().includes(needle));
});

const scopeWarning = computed(() =>
    scopeMode.value === "selected" && selectedProjectIds.value.length === 0
        ? "Pick at least one project, or switch back to all projects."
        : undefined
);

const canCreateToken = computed(
    () => newTokenName.value.trim().length > 0 && (scopeMode.value === "all" || selectedProjectIds.value.length > 0)
);

// Loading on tab activation rather than on dialog open keeps the two list
// requests off the path of someone who only came here to change a password.
watch(activeTab, async (tab) => {
    if (tab === "tokens") {
        await Promise.all([loadTokens(), loadProjects()]);
    } else if (tab === "ssh") {
        await loadSshKeys();
    }
});

/**
 * Renders a timestamp as a short relative age, the form these lists want:
 * whether a token was used an hour ago or last year is the question being
 * asked, not the exact minute it happened.
 *
 * @param iso An ISO 8601 timestamp
 * @return A phrase like "just now", "3 days ago", or "in 2 months"
 */
function formatRelative(iso: string): string {
    const then = new Date(iso).getTime();
    if (Number.isNaN(then)) {
        return "unknown";
    }
    const seconds = Math.round((then - Date.now()) / 1000);
    const units: [Intl.RelativeTimeFormatUnit, number][] = [
        ["year", 31536000],
        ["month", 2592000],
        ["week", 604800],
        ["day", 86400],
        ["hour", 3600],
        ["minute", 60]
    ];
    const formatter = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });
    for (const [unit, size] of units) {
        if (Math.abs(seconds) >= size) {
            return formatter.format(Math.round(seconds / size), unit);
        }
    }
    return "just now";
}

/**
 * @param lastUsedAt When the credential last authenticated, or null if never
 * @return The label to show for it
 */
function lastUsedLabel(lastUsedAt: string | null): string {
    return lastUsedAt ? `Last used ${formatRelative(lastUsedAt)}` : "Never used";
}

/**
 * @param expiresAt An expiry timestamp, or null when the token never expires
 * @return Whether that expiry has already passed
 */
function isExpired(expiresAt: string | null): boolean {
    return expiresAt != undefined && new Date(expiresAt).getTime() < Date.now();
}

function toggleProject(projectId: string) {
    selectedProjectIds.value = selectedProjectIds.value.includes(projectId)
        ? selectedProjectIds.value.filter((id) => id !== projectId)
        : [...selectedProjectIds.value, projectId];
}

async function handleSelectScopedMode() {
    scopeMode.value = "selected";
    if (availableProjects.value.length === 0) {
        await loadProjects();
    }
}

async function loadProjects() {
    if (workbenchState == undefined) {
        return;
    }
    const result = await workbenchState.backendApi.projects.getAll();
    if (result.success) {
        availableProjects.value = result.value;
    }
}

async function handlePasswordChange() {
    if (isUpdatingPassword.value) {
        return;
    }
    passwordError.value = undefined;
    passwordSuccess.value = undefined;

    if (!currentPassword.value || !newPassword.value) {
        passwordError.value = "Please provide your current and new password.";
        return;
    }

    if (newPassword.value !== confirmPassword.value) {
        passwordError.value = "New passwords do not match.";
        return;
    }

    isUpdatingPassword.value = true;
    try {
        const result = await authState.changePassword(currentPassword.value, newPassword.value);
        if (!result.success) {
            passwordError.value = result.error.message;
            return;
        }
        passwordSuccess.value = "Password updated successfully.";
        currentPassword.value = "";
        newPassword.value = "";
        confirmPassword.value = "";
    } finally {
        isUpdatingPassword.value = false;
    }
}

async function loadTokens() {
    tokensError.value = undefined;
    const result = await authState.listTokens();
    if (!result.success) {
        tokensError.value = result.error.message;
        return;
    }
    tokens.value = result.value;
}

async function handleCreateToken() {
    if (isCreatingToken.value || !canCreateToken.value) {
        return;
    }
    tokensError.value = undefined;
    isCreatingToken.value = true;
    try {
        const projectIds = scopeMode.value === "selected" ? selectedProjectIds.value : [];
        const result = await authState.createToken(newTokenName.value.trim(), undefined, projectIds);
        if (!result.success) {
            tokensError.value = result.error.message;
            return;
        }
        createdToken.value = result.value;
        newTokenName.value = "";
        scopeMode.value = "all";
        selectedProjectIds.value = [];
        projectSearch.value = "";
        await loadTokens();
    } finally {
        isCreatingToken.value = false;
    }
}

async function handleCopyToken() {
    if (!createdToken.value) {
        return;
    }
    // Guarded because the compose deployments serve the workbench over plain
    // HTTP, where navigator.clipboard is undefined.
    if (!navigator.clipboard) {
        tokensError.value = "Clipboard unavailable — select the token and copy it manually.";
        return;
    }
    try {
        await navigator.clipboard.writeText(createdToken.value.token);
        isTokenCopied.value = true;
        setTimeout(() => {
            isTokenCopied.value = false;
        }, 2000);
    } catch {
        tokensError.value = "Could not copy — select the token and copy it manually.";
    }
}

async function handleRevokeToken(tokenId: string) {
    if (revokingTokenId.value) {
        return;
    }
    tokensError.value = undefined;
    revokingTokenId.value = tokenId;
    try {
        const result = await authState.revokeToken(tokenId);
        if (!result.success) {
            tokensError.value = result.error.message;
            return;
        }
        tokens.value = tokens.value.filter((token) => token.id !== tokenId);
    } finally {
        revokingTokenId.value = undefined;
    }
}

async function loadSshKeys() {
    sshKeysError.value = undefined;
    const result = await authState.listSshKeys();
    if (!result.success) {
        sshKeysError.value = result.error.message;
        return;
    }
    sshKeys.value = result.value;
}

async function handleAddSshKey() {
    if (isAddingSshKey.value || !newSshKeyName.value.trim() || !newSshKeyValue.value.trim()) {
        return;
    }
    sshKeysError.value = undefined;
    isAddingSshKey.value = true;
    try {
        const result = await authState.addSshKey(newSshKeyName.value.trim(), newSshKeyValue.value.trim());
        if (!result.success) {
            sshKeysError.value = result.error.message;
            return;
        }
        newSshKeyName.value = "";
        newSshKeyValue.value = "";
        await loadSshKeys();
    } finally {
        isAddingSshKey.value = false;
    }
}

async function handleRemoveSshKey(keyId: string) {
    if (removingSshKeyId.value) {
        return;
    }
    sshKeysError.value = undefined;
    removingSshKeyId.value = keyId;
    try {
        const result = await authState.removeSshKey(keyId);
        if (!result.success) {
            sshKeysError.value = result.error.message;
            return;
        }
        sshKeys.value = sshKeys.value.filter((key) => key.id !== keyId);
    } finally {
        removingSshKeyId.value = undefined;
    }
}

async function handleLogout() {
    if (isLoggingOut.value) {
        return;
    }
    isLoggingOut.value = true;
    try {
        await authState.logout();
        open.value = false;
    } finally {
        isLoggingOut.value = false;
    }
}
</script>
