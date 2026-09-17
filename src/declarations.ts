/**
 * Server 對自己的宣告。
 *
 * 只有 `server/discover` 讀它。放在協定層之外，因為改動的理由不同：協定層隨規格改，
 * 這裡隨這個 Server 會做什麼改。
 */

/** 自報身分。規格說協議不驗證，只供顯示與除錯，不得用來做安全判斷。 */
export const SERVER_INFO = {
  name: 'project-mcp',
  title: 'project_mcp',
  version: '0.1.0',
} as const;

/** 只有 tools：沒有 Resources（讀取 Resource 無法回報失敗），也沒有 prompts。 */
export const CAPABILITIES = { tools: {} } as const;
