import type { ApiResult, CommonError } from "../apiResult";
import type { BackendApiCore } from "../backendApi";

/**
 * How git can be reached for this deployment. Asked for rather than assumed,
 * because the SSH port is configurable, is not exposed at all in some
 * deployments, and the OAuth paths the setup commands name have to be the
 * ones this server actually serves.
 */
export interface GitAccessConfig {
    sshPort: number;
    sshHost?: string;
    sshEnabled: boolean;
    oauthAuthorizePath: string;
    oauthTokenPath: string;
}

/**
 * API for how git access is configured on this deployment.
 */
export class GitApi {
    /**
     * Creates a new GitApi instance
     *
     * @param core The core backend API providing HTTP utilities
     */
    constructor(private readonly core: BackendApiCore) {}

    /**
     * Gets the deployment's git access configuration.
     */
    async getAccessConfig(): Promise<ApiResult<GitAccessConfig, CommonError>> {
        return this.core.fetchApiResult(`${this.core.baseUrl}/git/config`);
    }
}
