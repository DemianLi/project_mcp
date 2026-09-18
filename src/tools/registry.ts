/**
 * Server 宣告的 Tools。
 *
 * 加一個 Tool 只動這個陣列與它自己的檔案。
 */
import type { McpServer } from '@modelcontextprotocol/server';
import { getWeather } from './getWeather.js';
import type { RegisterableTool } from './definition.js';

/**
 * 順序固定。
 *
 * 規格說 `tools/list` 的順序 SHOULD 穩定；寫死成字面陣列就是最便宜的保證——SDK 按註冊
 * 順序列出，而註冊順序就是這個陣列的順序。
 */
export const TOOLS: readonly RegisterableTool[] = [getWeather];

export function registerTools(server: McpServer): void {
  for (const tool of TOOLS) {
    tool.register(server);
  }
}
