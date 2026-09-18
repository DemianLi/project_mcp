/**
 * Server 對自己的宣告。
 *
 * 協定的機制由 `@modelcontextprotocol/server` 負責；這裡只放「這一個 Server 是什麼」。
 * 改動的理由不同：SDK 隨規格改，這個檔案隨這個 Server 會做什麼改。
 */
import type { ServerCapabilities, ServerOptions } from '@modelcontextprotocol/server';

/** 自報身分。規格說協議不驗證，只供顯示與除錯，不得用來做安全判斷。 */
export const SERVER_INFO = {
  name: 'project-mcp',
  title: 'project_mcp',
  version: '0.1.0',
} as const;

/** 只有 tools：沒有 Resources（讀取 Resource 無法回報失敗），也沒有 prompts。 */
export const CAPABILITIES: ServerCapabilities = { tools: {} };

/**
 * 本 Server 寫的時候對著哪一版規格。
 *
 * SDK 沒有導出「modern 版」的常數（它導出的 `LATEST_PROTOCOL_VERSION` 講的是還能協商的
 * 舊版），所以這裡自己寫一份，並且由 wire 測試比對 `server/discover` 實際回報的值——
 * 兩邊一旦不一致，測試會紅，而不是靜靜地過期。
 */
export const PROTOCOL_VERSION = '2026-07-28';

/**
 * 清單類結果的快取提示。
 *
 * 2026-07-28 要求 `tools/list` 這類結果帶 `ttlMs` 與 `cacheScope`；SDK 的保守預設是
 * `ttlMs: 0`（等於不要快取）。Tools 寫死在程式裡，一次啟動之內不會變，一分鐘是保守值。
 * `private` 是因為之後接上資料庫的 Tool 會綁呼叫者的身分，不該讓共用代理留一份。
 */
export const CACHE_HINTS: NonNullable<ServerOptions['cacheHints']> = {
  'tools/list': { ttlMs: 60_000, cacheScope: 'private' },
};
