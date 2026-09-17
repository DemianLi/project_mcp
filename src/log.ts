/**
 * Log 一律寫 stderr。
 *
 * stdout 是協定通道，寫進去的任何一個字都會被 Client 當成 JSON-RPC 訊息解析而失敗。
 * 2026-07-28 把 `logging/setLevel` 標為棄用，改建議 Server 直接寫 stderr 或送
 * OpenTelemetry——這裡先走 stderr。
 *
 * 記的是 Shape（哪個方法、花多久、結果如何），不是 Content（issue 內文、留言）：
 * 內容一旦落進 log 就離開了協定通道，變成檔案系統層級的東西。
 */
export function log(event: Record<string, unknown>): void {
  process.stderr.write(`${JSON.stringify({ at: new Date().toISOString(), ...event })}\n`);
}
