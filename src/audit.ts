/**
 * 稽核紀錄：誰、在什麼時候、呼叫了哪個 Tool、結果如何。
 *
 * 跟一般的 log 分開，因為問的問題不同。一般的 log 是給維運看「這台跑得好不好」；
 * 稽核是給人事後回答「這筆資料是誰動的」。政府機關的案子通常後者是硬需求。
 *
 * 記的仍然是 Shape 不是 Content：哪個 Tool、成不成功、花多久、由誰呼叫——不記參數，
 * 也不記回傳的資料。內容一旦落進 log 就離開了協定通道，變成檔案系統層級的東西，
 * 而稽核要回答的問題不需要它。要看內容應該去查資料表本身。
 */
import { log } from './log.js';

export interface AuditEntry {
  /** 哪個機關。沒有驗證層時是 `anonymous`。 */
  readonly agency: string;
  readonly tool: string;
  readonly outcome: 'ok' | 'failed' | 'input_required' | 'threw';
  readonly ms: number;
}

export function audit(entry: AuditEntry): void {
  log({ event: 'audit', ...entry });
}
