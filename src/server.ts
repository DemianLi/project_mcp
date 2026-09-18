/**
 * 組出一個 Server 實例。
 *
 * `serveStdio` 要的是工廠而不是實例：一條連線的開場決定它講哪一版協定，然後才從工廠
 * 拿一個實例釘住。同一個工廠兩版都能服務，所以這裡不必知道自己被用在哪一版上。
 */
import { McpServer } from '@modelcontextprotocol/server';
import { CACHE_HINTS, CAPABILITIES, SERVER_INFO } from './declarations.js';
import { registerTools } from './tools/registry.js';

export function createServer(): McpServer {
  const server = new McpServer(SERVER_INFO, {
    capabilities: CAPABILITIES,
    cacheHints: CACHE_HINTS,
  });
  registerTools(server);
  return server;
}
