/**
 * stdio 上的行框架。
 *
 * 只搬位元組：一行一則讀進來，一行一則寫出去。不認識 JSON、不認識 MCP，
 * 因此換成別的 Transport 時，被換掉的只有這個檔案。
 */
import { createInterface } from 'node:readline';
import type { Readable, Writable } from 'node:stream';

export function readLines(input: Readable): AsyncIterable<string> {
  return createInterface({ input, crlfDelay: Infinity });
}

export function writeLine(output: Writable, text: string): void {
  output.write(`${text}\n`);
}
