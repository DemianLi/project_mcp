/**
 * 組出一個 Server 實例。
 *
 * `serveStdio` 要的是工廠而不是實例：一條連線的開場決定它講哪一版協定，然後才從工廠
 * 拿一個實例釘住。同一個工廠兩版都能服務，所以這裡不必知道自己被用在哪一版上。
 */
import { McpServer } from '@modelcontextprotocol/server';
import { CACHE_HINTS, CAPABILITIES, SERVER_INFO } from './declarations.js';
import { requestState } from './security/requestState.js';
import { registerTools } from './tools/registry.js';

export function createServer(): McpServer {
  const server = new McpServer(SERVER_INFO, {
    capabilities: CAPABILITIES,
    cacheHints: CACHE_HINTS,
    // 多回合流程回來的 `requestState` 先驗過再進 Tool。驗不過 SDK 直接回 -32602，
    // Tool 因此可以假設「讀得到就是自己封的」。不設這個 hook 的話，SDK 會把原始字串
    // 原樣交給 Tool——那是攻擊者控制的輸入。
    requestState: { verify: requestState.verify },
  });
  registerTools(server);
  return server;
}
