/**
 * Responses and WebSocket messages smaller than this are sent uncompressed: compressing them saves
 * less than it costs.
 */
export const COMPRESSION_THRESHOLD_BYTES = 1024;

/**
 * Largest WebSocket message between services: a whole model or result file travels in one message.
 * The Kotlin services use the same bound, `MAX_SERVICE_WEBSOCKET_MESSAGE_BYTES` in `common`.
 */
export const MAX_SERVICE_WEBSOCKET_MESSAGE_BYTES = 512 * 1024 * 1024;
