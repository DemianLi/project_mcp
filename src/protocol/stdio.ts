/**
 * stdio Transport：一行一則訊息，stdout 只走 MCP。
 *
 * 逐則序列處理，做完一則才讀下一則。這不是效能選擇而是契約選擇——交錯處理需要
 * 為每則請求各自管理狀態，而無狀態核心的價值正在於沒有那種狀態。
 */
import { createInterface } from 'node:readline';
import type { Readable, Writable } from 'node:stream';
import { ErrorCode, failure, parse } from './jsonrpc.js';
import { dispatch, dispatchNotification } from './dispatch.js';
import { log } from '../log.js';

export async function serve(input: Readable, output: Writable): Promise<void> {
  const lines = createInterface({ input, crlfDelay: Infinity });

  for await (const line of lines) {
    // 空行不是訊息。規格的行框架下它沒有意義，回 Parse error 只會製造噪音。
    if (line.trim() === '') {
      continue;
    }

    const parsed = parse(line);
    switch (parsed.kind) {
      case 'notification':
        dispatchNotification(parsed.notification);
        break;
      case 'error':
        write(output, parsed.response);
        break;
      case 'request': {
        const started = Date.now();
        try {
          const response = await dispatch(parsed.request);
          write(output, response);
          log({
            method: parsed.request.method,
            ms: Date.now() - started,
            outcome: 'error' in response ? 'error' : 'ok',
          });
        } catch (cause) {
          // 工具丟出例外不該弄死 Server：回一則 internal error，繼續讀下一行。
          write(output, failure(parsed.request.id, ErrorCode.InternalError, 'Internal error'));
          log({ method: parsed.request.method, ms: Date.now() - started, outcome: 'threw', cause: String(cause) });
        }
        break;
      }
    }
  }
}

function write(output: Writable, message: unknown): void {
  output.write(`${JSON.stringify(message)}\n`);
}
