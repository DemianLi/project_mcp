/**
 * 把一行文字解碼成請求或通知。
 *
 * 只負責解碼與格式檢查，不認識任何 method，也不決定怎麼回答。
 */
import { ErrorCode } from './errors.js';
import {
  JSONRPC_VERSION,
  failure,
  type JsonRpcFailure,
  type JsonRpcId,
  type JsonRpcNotification,
  type JsonRpcRequest,
} from './messages.js';

export type Parsed =
  | { readonly kind: 'request'; readonly request: JsonRpcRequest }
  | { readonly kind: 'notification'; readonly notification: JsonRpcNotification }
  | { readonly kind: 'error'; readonly response: JsonRpcFailure };

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** 讀得出 id 就回傳，否則 null——錯誤回應要盡量把 id 帶回去，好讓呼叫端對得上。 */
export function readId(value: unknown): JsonRpcId | null {
  if (!isObject(value)) {
    return null;
  }
  const id = value['id'];
  return typeof id === 'string' || typeof id === 'number' ? id : null;
}

/** 批次（JSON-RPC 的陣列形式）一律拒絕：MCP 沒有用到它。 */
export function parse(line: string): Parsed {
  let value: unknown;
  try {
    value = JSON.parse(line);
  } catch {
    return { kind: 'error', response: failure(null, ErrorCode.ParseError, 'Parse error') };
  }

  if (!isObject(value) || value['jsonrpc'] !== JSONRPC_VERSION) {
    return {
      kind: 'error',
      response: failure(readId(value), ErrorCode.InvalidRequest, 'Not a JSON-RPC 2.0 message'),
    };
  }

  const method = value['method'];
  if (typeof method !== 'string') {
    return {
      kind: 'error',
      response: failure(readId(value), ErrorCode.InvalidRequest, 'Missing method'),
    };
  }

  const rawParams = value['params'];
  if (rawParams !== undefined && !isObject(rawParams)) {
    return {
      kind: 'error',
      response: failure(readId(value), ErrorCode.InvalidRequest, 'params must be an object'),
    };
  }
  const params = rawParams as Record<string, unknown> | undefined;

  const id = readId(value);
  if (id === null) {
    if ('id' in value && value['id'] !== null) {
      return {
        kind: 'error',
        response: failure(null, ErrorCode.InvalidRequest, 'id must be a string or a number'),
      };
    }
    return {
      kind: 'notification',
      notification: params === undefined
        ? { jsonrpc: JSONRPC_VERSION, method }
        : { jsonrpc: JSONRPC_VERSION, method, params },
    };
  }

  return {
    kind: 'request',
    request: params === undefined
      ? { jsonrpc: JSONRPC_VERSION, id, method }
      : { jsonrpc: JSONRPC_VERSION, id, method, params },
  };
}
