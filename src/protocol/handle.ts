/**
 * 一行文字進，一行文字出。
 *
 * 協定層的頂點：解碼、分派、編碼。完全不碰 stream，因此測它不需要子行程，
 * 也不需要假的 stdin。
 */
import { ErrorCode } from './errors.js';
import { failure } from './messages.js';
import { parse } from './parse.js';
import { route, routeNotification } from './router.js';

export interface Exchange {
  /** 要寫回去的一行。通知沒有回應，因此可能沒有。 */
  readonly response?: string;
  /**
   * 這次交換的 Shape：哪個方法、結果如何。
   *
   * 只有形狀，沒有內容——issue 內文與留言一旦進到這裡就會離開協定通道，
   * 變成檔案系統層級的東西。
   */
  readonly shape: Record<string, unknown>;
}

/** 空行不是訊息：行框架下它沒有意義，回 Parse error 只會製造噪音。 */
export async function handle(line: string): Promise<Exchange | null> {
  if (line.trim() === '') {
    return null;
  }

  const parsed = parse(line);
  switch (parsed.kind) {
    case 'notification':
      routeNotification(parsed.notification);
      return { shape: { method: parsed.notification.method, outcome: 'noted' } };
    case 'error':
      return {
        response: JSON.stringify(parsed.response),
        shape: { outcome: 'rejected', code: parsed.response.error.code },
      };
    case 'request':
      try {
        const response = await route(parsed.request);
        return {
          response: JSON.stringify(response),
          shape: {
            method: parsed.request.method,
            outcome: 'error' in response ? 'error' : 'ok',
          },
        };
      } catch (cause) {
        // 工具丟出例外不該弄死 Server：回一則 internal error，繼續讀下一行。
        return {
          response: JSON.stringify(failure(parsed.request.id, ErrorCode.InternalError, 'Internal error')),
          shape: { method: parsed.request.method, outcome: 'threw', cause: String(cause) },
        };
      }
  }
}
