/**
 * A long-lived binary connection a plugin target accepts.
 *
 * A session is just a name for a connection. The platform owns the address it is reached at,
 * the token that authorizes it, its lifetime, its liveness and the helpers that encode what
 * travels on it. What actually travels is a protocol the plugin defines: the platform reads
 * none of it, correlates none of it, and defines no envelope or error shape inside it.
 *
 * Because the platform ascribes no meaning to the contents, two conversations that are not
 * the same conversation are two session types, not one type with a multiplexed vocabulary.
 */
export interface SessionType {
    /**
     * Name of the protocol spoken on this session, owned by whoever defines the contract.
     * `script-functions`, for instance, is defined by the script language, not by the platform.
     */
    protocol: string;

    /**
     * Protocol versions this side can speak, most preferred first. A connection is refused
     * unless the caller asks for one of these.
     */
    versions: number[];
}

/**
 * The session types a target declares, keyed by session name.
 *
 * The name is the last segment of the address a caller connects to, so it is unique within
 * one target rather than globally.
 */
export type SessionTypes = Record<string, SessionType>;
