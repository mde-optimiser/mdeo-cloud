<template>
    <div class="w-full max-w-md space-y-6">
        <Card>
            <CardHeader class="gap-4 text-center">
                <div class="flex items-center justify-center gap-3">
                    <Icon :showText="true" class="h-14 w-14" />
                    <p class="text-lg font-semibold tracking-[0.15em] text-foreground">MDEO</p>
                </div>
                <div>
                    <CardTitle>Authorize git access</CardTitle>
                </div>
            </CardHeader>
            <CardContent class="pt-0">
                <FieldGroup>
                    <Field v-if="requestError">
                        <FieldError :errors="[requestError]" />
                    </Field>

                    <template v-else-if="!isLoading">
                        <Field>
                            <FieldContent>
                                <div class="rounded-lg border border-border p-4 text-sm text-muted-foreground">
                                    Your git client is asking for an access token for
                                    <span class="font-medium text-foreground">{{ username }}</span
                                    >. It can
                                    <span class="font-medium text-foreground"
                                        >read and write the projects you have access to</span
                                    >, is saved by your git credential helper, and can be revoked any time from
                                    <span class="font-medium text-foreground">Account → Access tokens</span>.
                                </div>
                            </FieldContent>
                        </Field>

                        <Field v-if="decisionError">
                            <FieldError :errors="[decisionError]" />
                        </Field>

                        <Field>
                            <FieldContent class="gap-2">
                                <Button type="button" class="w-full" :disabled="isDeciding" @click="decide(true)">
                                    <span v-if="!isDeciding">Authorize</span>
                                    <span v-else class="animate-pulse">Authorizing…</span>
                                </Button>
                                <Button
                                    type="button"
                                    variant="outline"
                                    class="w-full"
                                    :disabled="isDeciding"
                                    @click="decide(false)"
                                >
                                    Cancel
                                </Button>
                            </FieldContent>
                            <FieldDescription class="text-center">
                                You can close this tab once your git client continues.
                            </FieldDescription>
                        </Field>
                    </template>
                </FieldGroup>
            </CardContent>
        </Card>
    </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import Icon from "../Icon.vue";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Field, FieldContent, FieldDescription, FieldError, FieldGroup } from "@/components/ui/field";
import type { BackendApi } from "@/data/api/backendApi";
import { readGitOAuthRequest } from "@/data/gitOAuthRequest";

const props = defineProps<{
    backendApi: BackendApi;
    username: string;
}>();

const isLoading = ref(true);
const isDeciding = ref(false);
const requestError = ref<string>();
const decisionError = ref<string>();

const request = readGitOAuthRequest();

onMounted(async () => {
    if (request == undefined) {
        requestError.value = "This git sign-in link is missing information. Start the clone again.";
        isLoading.value = false;
        return;
    }
    // Checked before anything is offered for approval, so a malformed request
    // is reported as one rather than presented as a legitimate thing to allow.
    const result = await props.backendApi.auth.getGitOAuthRequestInfo(request);
    if (!result.success) {
        requestError.value = result.error.message;
    }
    isLoading.value = false;
});

async function decide(approve: boolean) {
    if (request == undefined || isDeciding.value) {
        return;
    }
    decisionError.value = undefined;
    isDeciding.value = true;
    try {
        const result = await props.backendApi.auth.decideGitOAuth(request, approve);
        if (!result.success) {
            decisionError.value = result.error.message;
            return;
        }
        // The redirect target is a loopback address the backend validated, and
        // is where the waiting credential helper is listening.
        window.location.replace(result.value.redirectTo);
    } finally {
        isDeciding.value = false;
    }
}
</script>
