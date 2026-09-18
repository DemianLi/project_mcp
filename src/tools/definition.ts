/**
 * 一個 Tool 長什麼樣子。
 *
 * 宣告的部分（名字、說明、schema、annotations）會原樣出現在 `tools/list`；
 * `call` 不會——菜單上不寫怎麼做菜。
 */
import type { CallToolBody } from './result.js';

export interface ToolDefinition {
  readonly name: string;
  readonly title?: string;
  readonly description: string;
  /** JSON Schema。Client 拿它驗參數，也拿它給模型看。 */
  readonly inputSchema: Readonly<Record<string, unknown>>;
  /**
   * Tool 對自己的宣告（唯讀、破壞性、冪等）。
   *
   * 規格要求 Client MUST 把這些當成不可信的自述；本 Server 也不因為它們改變行為，
   * 它們的作用是宣告與測試分區。
   */
  readonly annotations?: Readonly<Record<string, unknown>>;
  readonly call: (args: Readonly<Record<string, unknown>>) => Promise<CallToolBody>;
}
