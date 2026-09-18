/**
 * 多回合流程（MRTR）裡那份「自己封好、交給 Client 保管、下一回合再拿回來」的狀態。
 *
 * 規格把它講得很清楚：`requestState` 會經過 Client 的手，回到 Server 時必須當成
 * 攻擊者控制的輸入。SDK 預設不做任何保護——沒有設 verify 的話，
 * `ctx.mcpReq.requestState()` 回的就是原始字串。所以這裡用 SDK 提供的 HMAC codec 封起來：
 * 改過的、過期的、換一個 method 回來的，都會在進到 Tool 之前就被擋掉（-32602）。
 */
import { randomBytes } from 'node:crypto';
import { createRequestStateCodec, type ServerContext } from '@modelcontextprotocol/server';
import { agencyOf } from '../auth/context.js';
import { log } from '../log.js';

/**
 * 封進狀態裡的東西。
 *
 * 帶 `tool` 是為了讓「某個 Tool 的確認」不能被拿去確認另一個 Tool。多一個做多回合的
 * Tool 就在這個聯集多一個成員。
 */
export type RequestState = { readonly tool: 'delete_note'; readonly noteId: number };

/** 秘密夠不夠長；createRequestStateCodec 要求至少 32 bytes。 */
const MIN_SECRET_BYTES = 32;

/**
 * 狀態多久之內算數。這是「人按下確認」的時間尺度，不是連線的尺度。
 */
const TTL_SECONDS = 300;

function readKey(): Uint8Array | string {
  const configured = process.env['REQUEST_STATE_SECRET'];
  if (configured !== undefined && Buffer.byteLength(configured) >= MIN_SECRET_BYTES) {
    return configured;
  }
  if (configured !== undefined) {
    log({ event: 'requestState.secretTooShort', needBytes: MIN_SECRET_BYTES, using: 'random' });
  }
  // 走 stdio 時一個行程服務整個流程，所以開機時隨機一把就夠。換成 HTTP、或是多個行程
  // 可能接到同一段流程的不同回合時，就必須設 REQUEST_STATE_SECRET 讓它們共用同一把。
  return randomBytes(MIN_SECRET_BYTES);
}

export const requestState = createRequestStateCodec<RequestState>({
  key: readKey(),
  ttlSeconds: TTL_SECONDS,
  /**
   * 綁住 method 與機關。
   *
   * method：在 `tools/call` 封的狀態不能拿去 `prompts/get` 用。
   * 機關：甲機關拿到的確認不能被乙機關重送——即使兩邊看的是同一份資料，「誰批准了這次
   * 刪除」也必須對得起來，否則稽核紀錄會指向錯的人。
   *
   * 綁定的值不會出現在 Client 拿到的字串裡（codec 把它做成 HMAC 標籤），所以機關代碼
   * 不會因此外流。
   */
  bind: (ctx: ServerContext) => `${ctx.mcpReq.method}\u0000${agencyOf(ctx)}`,
});
