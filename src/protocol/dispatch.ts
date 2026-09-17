/**
 * 依 method 把一則請求送到對的地方。
 *
 * 每一則請求都自己走完全程：讀 `_meta`、分派、包結果。沒有跨請求的狀態，
 * 也沒有「這條連線先前說過什麼」——那正是 2026-07-28 的無狀態核心。
 */
import {
  ErrorCode,
  failure,
  success,
  type JsonRpcNotification,
  type JsonRpcRequest,
  type JsonRpcResponse,
} from './jsonrpc.js';
import { readMeta } from './meta.js';
import { complete, completeList } from './results.js';
import { SERVER_INFO, SUPPORTED_PROTOCOL_VERSIONS } from './versions.js';
import { TOOLS, describe, findTool } from '../tools/registry.js';

/** 菜單多久算新鮮。Tools 寫死在程式裡，一次啟動之內不會變，一分鐘是保守值。 */
const TOOLS_LIST_TTL_MS = 60_000;

/**
 * 本 Server 宣告的能力。
 *
 * 只有 tools：沒有 Resources（讀取 Resource 無法回報失敗），也沒有 prompts。
 */
const CAPABILITIES = { tools: {} } as const;

export async function dispatch(request: JsonRpcRequest): Promise<JsonRpcResponse> {
  // server/discover 不檢查版本：它正是「你會哪幾版」的問法，要求先講對版本才能問，
  // 等於要求 Client 先知道答案。規格也把它當成 stdio 上的新舊版探測手段。
  if (request.method === 'server/discover') {
    return success(request.id, discover());
  }

  const meta = readMeta(request.params, request.id);
  if (!meta.ok) {
    return meta.response;
  }

  switch (request.method) {
    case 'tools/list':
      return success(request.id, listTools());
    case 'tools/call':
      return await callTool(request);
    default:
      return failure(request.id, ErrorCode.MethodNotFound, `Unknown method: ${request.method}`);
  }
}

/**
 * 通知沒有回應。
 *
 * `notifications/cancelled` 在 stdio 上是 Client 取消請求的唯一途徑。本 Server 目前
 * 逐則同步處理，一則做完才讀下一則，取消通知抵達時該則早已回覆完畢，因此收下不做事；
 * 真正要處理的時機是工具開始做長時間的下游呼叫之後。
 */
export function dispatchNotification(_notification: JsonRpcNotification): void {
  // 規格：未知的通知一律忽略，不得回錯誤。
}

function discover(): Record<string, unknown> {
  return complete({
    supportedVersions: [...SUPPORTED_PROTOCOL_VERSIONS],
    capabilities: CAPABILITIES,
    serverInfo: SERVER_INFO,
  });
}

function listTools(): Record<string, unknown> {
  return completeList({ tools: TOOLS.map(describe) }, TOOLS_LIST_TTL_MS);
}

async function callTool(request: JsonRpcRequest): Promise<JsonRpcResponse> {
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
  return success(request.id, complete(result));
}
