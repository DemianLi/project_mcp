/**
 * Server 宣告的 Tools。
 *
 * 加一個 Tool 只動這個陣列與它自己的檔案。
 */
import type { McpServer } from '@modelcontextprotocol/server';
import { getWeather } from './getWeather.js';
import { addNote } from './notes/addNote.js';
import { deleteNote } from './notes/deleteNote.js';
import { listNotes } from './notes/listNotes.js';
import type { RegisterableTool } from './definition.js';

/**
 * 順序固定。
 *
 * 規格說 `tools/list` 的順序 SHOULD 穩定；寫死成字面陣列就是最便宜的保證——SDK 按註冊
 * 順序列出，而註冊順序就是這個陣列的順序。
 */
export const TOOLS: readonly RegisterableTool[] = [getWeather, listNotes, addNote, deleteNote];

export function registerTools(server: McpServer): void {
  for (const tool of TOOLS) {
    tool.register(server);
  }
}

/**
 * 某個 Tool 需要哪個 scope。
 *
 * HTTP 那層用它做符合 RFC 6750 的快速拒絕；真正的關卡在 `defineTool`，因為那裡才確定
 * 是哪個 Tool 要跑。兩邊查的是同一份資料，所以不會各說各話。不認得的名字回 undefined，
 * 讓 SDK 去回「沒有這個 Tool」，而不是在這裡多發明一種錯誤。
 */
export function scopeFor(name: string): string | undefined {
  return TOOLS.find((tool) => tool.name === name)?.requiredScope;
}
