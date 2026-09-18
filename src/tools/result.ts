/**
 * `tools/call` 的結果外殼：成功與失敗各一個建構函式。
 *
 * 兩者都是成功的 JSON-RPC 回應，差別只在 `isError`。`content` 給模型讀，
 * `structuredContent` 給程式讀，兩邊講同一件事。
 */
import type { Remedy } from './remedy.js';

export interface CallToolBody {
  readonly content: readonly { readonly type: 'text'; readonly text: string }[];
  readonly structuredContent: Readonly<Record<string, unknown>>;
  readonly isError: boolean;
}

export function ok(structured: Record<string, unknown>): CallToolBody {
  return {
    content: [{ type: 'text', text: JSON.stringify(structured) }],
    structuredContent: structured,
    isError: false,
  };
}

/**
 * 失敗。`message` 是給人看的句子，不屬於公開契約；`remedy` 才是。
 */
export function failed(
  remedy: Remedy,
  message: string,
  extra: Record<string, unknown> = {},
): CallToolBody {
  const structured = { remedy, message, ...extra };
  return {
    content: [{ type: 'text', text: message }],
    structuredContent: structured,
    isError: true,
  };
}
