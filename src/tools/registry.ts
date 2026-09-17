/**
 * Server 宣告的 Tools。
 *
 * 骨架階段是空的：Client 連得上、問得到菜單，菜單上沒有菜。第一個 Tool 落地時，
 * 改的是這個陣列，不是分派層。
 */

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
  readonly call: (args: Readonly<Record<string, unknown>>) => Promise<Record<string, unknown>>;
}

/**
 * 順序固定。
 *
 * 規格說 `tools/list` 的順序 SHOULD 穩定；寫死成字面陣列就是最便宜的保證，
 * 不必依賴掃描或註冊的先後。
 */
export const TOOLS: readonly ToolDefinition[] = [];

export function findTool(name: string): ToolDefinition | undefined {
  return TOOLS.find((tool) => tool.name === name);
}

/** `tools/list` 對外的形狀：不含 `call`。 */
export function describe(tool: ToolDefinition): Record<string, unknown> {
  const { call: _call, ...declared } = tool;
  return declared;
}
