/**
 * 誰可以呼叫哪個 Tool。
 *
 * 檢查有兩處，兩處查同一份對照表：
 *
 * 1. HTTP 路由，依 `Mcp-Name` 回 403 `insufficient_scope`。這是實際擋下請求的那一處，
 *    也是符合 RFC 6750 的答案——呼叫者是程式，它的 HTTP client 看得懂 403，看不懂一個
 *    包在 200 裡的錯誤結果。
 * 2. Tool 分派（`defineTool`），依實際要跑的 Tool 再檢查一次。
 *
 * 第二處今天在 HTTP 上碰不到：`Mcp-Name` 與 `params.name` 不一致時 SDK 會先回 -32020，
 * 省略 header 也一樣，所以每條路不是被第一處擋就是被 SDK 擋。它留著的理由是不要讓
 * 授權的正確性「依賴 SDK 的 header 檢查存在且正確」——換一種沒有那個 header 的傳輸
 * （行程內、或日後的其他 binding）時，授權仍然成立。
 */
import type { ServerContext } from '@modelcontextprotocol/server';

/** 這個骨架用到的三級。加 Tool 時如果需要新的分級，加在這裡。 */
export const Scope = {
  NotesRead: 'notes:read',
  NotesWrite: 'notes:write',
  NotesDelete: 'notes:delete',
} as const;

export type Scope = (typeof Scope)[keyof typeof Scope];

/**
 * 這個請求帶著哪些 scope。
 *
 * `null` 表示「這台沒有驗證層」——走 stdio，或 HTTP 設了 `MCP_AUTH_MODE=none`。沒有身分
 * 就沒有授權可言，那種部署已經被啟動守門限制在 loopback 上了。這跟「有身分但一個 scope
 * 都沒有」是兩回事，所以用 `null` 而不是空陣列。
 */
export function scopesOf(ctx: ServerContext): readonly string[] | null {
  const authInfo = ctx.http?.authInfo;
  if (authInfo === undefined) {
    return null;
  }
  return authInfo.scopes;
}

/** 允許嗎。Tool 沒宣告需要的 scope 就是任何通過驗證的人都能呼叫。 */
export function allows(scopes: readonly string[] | null, required: string | undefined): boolean {
  if (required === undefined || scopes === null) {
    return true;
  }
  return scopes.includes(required);
}
