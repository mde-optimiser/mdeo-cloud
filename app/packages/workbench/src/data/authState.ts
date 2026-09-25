import { computed, ref } from "vue";
import type { BackendApi, User } from "./api/backendApi";

/**
 * Manager for authentication state
 */
export class AuthState {
    /**
     * Currently authenticated user (if any)
     */
    readonly user = ref<User | undefined>();

    /**
     * Whether a user is currently authenticated
     */
    readonly isAuthenticated = computed(() => this.user.value != undefined);

    /**
     * Convenience flag indicating an administrator
     */
    readonly isAdmin = computed(() => this.user.value?.isAdmin ?? false);

    /**
     * Error message from authentication attempts
     */
    readonly authError = ref<string>();

    /**
     * Whether an authentication request is in progress
     */
    readonly isAuthenticating = ref(false);

    /**
     * Creates a new authentication state manager
     *
     * @param backendApi The backend API instance
     * @param onLogout Optional callback to execute after logout (e.g., to reset workbench)
     */
    constructor(
        readonly backendApi: BackendApi,
        private readonly onLogout?: () => void | Promise<void>
    ) {}

    /**
     * Checks if a user is already authenticated via session
     * Should be called on application startup
     */
    async checkAuthentication(): Promise<void> {
        try {
            const result = await this.backendApi.auth.getCurrentUser();
            if (result.success) {
                this.user.value = result.value;
            }
        } catch {
            // Ignore, user not authenticated
        }
    }

    /**
     * Performs login with the provided credentials
     *
     * @param username The username
     * @param password The password
     * @returns The authenticated user on success
     */
    async login(username: string, password: string): Promise<{ success: boolean; user?: User; error?: string }> {
        this.authError.value = undefined;
        this.isAuthenticating.value = true;
        try {
            const result = await this.backendApi.auth.login(username, password);
            if (!result.success) {
                this.authError.value = result.error.message;
                return { success: false, error: result.error.message };
            }
            this.user.value = result.value;
            return { success: true, user: result.value };
        } finally {
            this.isAuthenticating.value = false;
        }
    }

    /**
     * Performs registration with the provided credentials
     *
     * @param username The username
     * @param password The password
     * @returns The authenticated user on success
     */
    async register(username: string, password: string): Promise<{ success: boolean; user?: User; error?: string }> {
        this.authError.value = undefined;
        this.isAuthenticating.value = true;
        try {
            const result = await this.backendApi.auth.register(username, password);
            if (!result.success) {
                this.authError.value = result.error.message;
                return { success: false, error: result.error.message };
            }
            this.user.value = result.value;
            return { success: true, user: result.value };
        } finally {
            this.isAuthenticating.value = false;
        }
    }

    /**
     * Logs out the current user
     */
    async logout(): Promise<void> {
        await this.backendApi.auth.logout();
        this.user.value = undefined;
        if (this.onLogout) {
            await this.onLogout();
        }
    }

    /**
     * Changes the current user's password
     *
     * @param currentPassword The current password
     * @param newPassword The new password
     */
    async changePassword(currentPassword: string, newPassword: string) {
        return this.backendApi.auth.changePassword(currentPassword, newPassword);
    }

    /**
     * Creates a new personal access token for the current user.
     *
     * @param name A label to tell this token apart from others later
     * @param expiresAt When the token stops working, or undefined for no expiry
     * @param projectIds Projects to restrict the token to; empty means every
     *   project the user can reach
     */
    async createToken(name: string, expiresAt?: string, projectIds: string[] = []) {
        return this.backendApi.auth.createToken(name, expiresAt, projectIds);
    }

    /**
     * Lists the current user's own personal access tokens.
     */
    async listTokens() {
        return this.backendApi.auth.listTokens();
    }

    /**
     * Revokes one of the current user's own personal access tokens.
     *
     * @param tokenId The token to revoke
     */
    async revokeToken(tokenId: string) {
        return this.backendApi.auth.revokeToken(tokenId);
    }

    /**
     * Registers a new SSH public key for the current user.
     *
     * @param name A label to tell this key apart from others later
     * @param publicKey The full authorized_keys-format line
     */
    async addSshKey(name: string, publicKey: string) {
        return this.backendApi.auth.addSshKey(name, publicKey);
    }

    /**
     * Lists the current user's own registered SSH public keys.
     */
    async listSshKeys() {
        return this.backendApi.auth.listSshKeys();
    }

    /**
     * Removes one of the current user's own registered SSH public keys.
     *
     * @param keyId The key to remove
     */
    async removeSshKey(keyId: string) {
        return this.backendApi.auth.removeSshKey(keyId);
    }
}
