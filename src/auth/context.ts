/**
 * 從 Server 的請求上下文取出機關代碼。
 *
 * SDK 把驗證結果原樣放在 `ctx.http.authInfo`（HTTP 那條由我們自己填），stdio 那條沒有
 * 這個欄位。兩邊都要有一個值可以寫進稽核紀錄與多回合狀態，所以缺的時候給 `ANONYMOUS`。
 */
import type { ServerContext } from '@modelcontextprotocol/server';
import { ANONYMOUS } from './principal.js';

export function agencyOf(ctx: ServerContext): string {
  const clientId = ctx.http?.authInfo?.clientId;
  return typeof clientId === 'string' && clientId !== '' ? clientId : ANONYMOUS.agency;
}
