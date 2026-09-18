#!/usr/bin/env node
/**
 * 對已建置的 Server 問一句話，把回應印出來。
 *
 * 2026-07-28 每一則請求都要自帶 `_meta`，手打很痛；這個腳本只負責把它填上。
 *
 *   node scripts/ask.mjs server/discover
 *   node scripts/ask.mjs tools/list
 *   node scripts/ask.mjs tools/call '{"name":"get_weather","arguments":{"city":"Taipei"}}'
 *
 * 預設問本機建好的 dist/main.js。要問別的地方（例如容器裡的 Server）就設 MCP_SERVER_CMD：
 *
 *   MCP_SERVER_CMD='docker run -i --rm project-mcp' node scripts/ask.mjs tools/list
 */
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';

const [method, rawParams] = process.argv.slice(2);
if (method === undefined) {
  console.error('usage: node scripts/ask.mjs <method> [params-json]');
  process.exit(2);
}

const override = process.env['MCP_SERVER_CMD'];
const [command, ...commandArgs] = override === undefined
  ? [process.execPath, fileURLToPath(new URL('../dist/main.js', import.meta.url))]
  : override.split(' ').filter((part) => part !== '');
const child = spawn(command, commandArgs, { stdio: ['pipe', 'pipe', 'inherit'] });

child.stdin.write(`${JSON.stringify({
  jsonrpc: '2.0',
  id: 1,
  method,
  params: {
    ...(rawParams === undefined ? {} : JSON.parse(rawParams)),
    _meta: {
      'io.modelcontextprotocol/protocolVersion': '2026-07-28',
      'io.modelcontextprotocol/clientCapabilities': {},
      'io.modelcontextprotocol/clientInfo': { name: 'ask.mjs', version: '0.1.0' },
    },
  },
})}\n`);
child.stdin.end();

for await (const line of createInterface({ input: child.stdout })) {
  console.log(JSON.stringify(JSON.parse(line), null, 2));
}
