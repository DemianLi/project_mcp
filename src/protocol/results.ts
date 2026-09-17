/**
 * 每個 result 都要帶的 `resultType`，以及清單類結果的快取提示。
 *
 * 這些欄位在 2026-07-28 是 MUST，而不是可加可不加的裝飾。集中在這裡是為了讓
 * 「忘記帶」變成不可能：分派層不自己拼結果物件，一律經過這兩個函式。
 */

/** 正常結果。舊版沒有這個欄位，Client 會當成 complete，但本 Server 一律明寫。 */
export type ResultType = 'complete' | 'input_required';

/** 共用代理可不可以快取。本 Server 的回應綁 `gh` 的登入身分，因此一律 private。 */
export type CacheScope = 'public' | 'private';

export function complete(body: Record<string, unknown> = {}): Record<string, unknown> {
  // resultType 放在最後，呼叫端就無法用 body 裡同名的鍵蓋掉它。要回 input_required
  // 的是另一個建構函式，不是「湊巧帶了那個鍵」。
  return { ...body, resultType: 'complete' satisfies ResultType };
}

/**
 * 清單類結果：除了 `resultType` 還 MUST 帶 `ttlMs` 與 `cacheScope`。
 *
 * ttlMs 是「幾毫秒內算新鮮」，不是輪詢間隔；規格明說 Client SHOULD NOT 拿它當鬧鐘。
 */
export function completeList(
  body: Record<string, unknown>,
  ttlMs: number,
  cacheScope: CacheScope = 'private',
): Record<string, unknown> {
  return { ...body, resultType: 'complete' satisfies ResultType, ttlMs, cacheScope };
}
