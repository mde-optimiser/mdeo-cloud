/**
 * Responses and WebSocket messages smaller than this are sent uncompressed: compressing them saves
 * less than it costs.
 */
export const COMPRESSION_THRESHOLD_BYTES = 1024;
