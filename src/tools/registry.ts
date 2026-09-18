/**
 * Server 宣告的 Tools。
 *
 * 加一個 Tool 只動這個陣列與它自己的檔案，協定層不必知道。
 */
import { getWeather } from './getWeather.js';
import type { ToolDefinition } from './definition.js';

/**
 * 順序固定。
 *
 * 規格說 `tools/list` 的順序 SHOULD 穩定；寫死成字面陣列就是最便宜的保證。
 */
export const TOOLS: readonly ToolDefinition[] = [getWeather];

export function findTool(name: string): ToolDefinition | undefined {
  return TOOLS.find((tool) => tool.name === name);
}

/** `tools/list` 對外的形狀：不含 `call`。 */
export function describe(tool: ToolDefinition): Record<string, unknown> {
  const { call: _call, ...declared } = tool;
  return declared;
}
