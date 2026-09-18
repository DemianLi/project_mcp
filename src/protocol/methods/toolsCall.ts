/**
 * `tools/call`：點菜。
 */
import { ErrorCode } from '../errors.js';
import { failure, success, type JsonRpcRequest, type JsonRpcResponse } from '../messages.js';
import { complete } from '../results.js';
import { findTool } from '../../tools/registry.js';

export async function callTool(request: JsonRpcRequest): Promise<JsonRpcResponse> {
  const name = request.params?.['name'];
  if (typeof name !== 'string') {
    return failure(request.id, ErrorCode.InvalidParams, 'params.name is required');
  }

  const tool = findTool(name);
  if (tool === undefined) {
    // 未知的工具是參數錯，不是 method 不存在：tools/call 這個 method 確實存在。
    return failure(request.id, ErrorCode.InvalidParams, `Unknown tool: ${name}`);
  }

  const args = request.params?.['arguments'];
  const result = await tool.call(
    typeof args === 'object' && args !== null && !Array.isArray(args)
      ? (args as Record<string, unknown>)
      : {},
  );
  // 展開一次，把 Tool 的具名型別攤成結果物件。
  return success(request.id, complete({ ...result }));
}
