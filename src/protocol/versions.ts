/**
 * 本 Server 講的 MCP 版本。
 *
 * 2026-07-28 起規格自稱 Modern：沒有 initialize 握手，版本與能力改由每一則請求的
 * `_meta` 自帶。2025-11-25 以前那種靠握手記住狀態的做法叫 Legacy，本 Server 不實作，
 * 因為兩者的核心（有狀態 vs 無狀態）互斥，同時支援等於維護兩個 Server。
 */
export const PROTOCOL_VERSION = '2026-07-28';

/**
 * `server/discover` 回報的版本清單，也是版本檢查的依據。
 *
 * 之所以是陣列而不是單一字串：規格的相容矩陣容許 Dual-era Server，日後若要接回
 * Legacy Client，新增的是這裡的項目與對應的分派路徑，而不是改動呼叫端。
 */
export const SUPPORTED_PROTOCOL_VERSIONS: readonly string[] = [PROTOCOL_VERSION];

/** 自報身分。規格說雙方都不驗證，只供顯示與除錯，不得用來做安全判斷。 */
export const SERVER_INFO = {
  name: 'project-mcp',
  title: 'project_mcp',
  version: '0.1.0',
} as const;
