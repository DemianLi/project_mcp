/**
 * 本 Server 講的 MCP 版本。
 *
 * 陣列而不是單一字串：日後要接回舊版，新增的是這裡的項目與對應的分派路徑，
 * 而不是改動呼叫端。
 */
export const PROTOCOL_VERSION = '2026-07-28';

export const SUPPORTED_PROTOCOL_VERSIONS: readonly string[] = [PROTOCOL_VERSION];
