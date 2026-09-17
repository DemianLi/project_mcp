/**
 * JSON-RPC 2.0 的線上格式，以及本 Server 用得到的錯誤碼。
 *
 * 只描述形狀，不含任何 MCP 語意——MCP 的部分在 meta.ts 與 dispatch.ts。
 */

export const JSONRPC_VERSION = '2.0';

export type JsonRpcId = string | number;

export interface JsonRpcRequest {
  readonly jsonrpc: typeof JSONRPC_VERSION;
  readonly id: JsonRpcId;
  readonly method: string;
  readonly params?: Readonly<Record<string, unknown>>;
}

/** 通知沒有 id，因此也不會有回應。本 Server 只收 `notifications/cancelled`。 */
export interface JsonRpcNotification {
  readonly jsonrpc: typeof JSONRPC_VERSION;
  readonly method: string;
  readonly params?: Readonly<Record<string, unknown>>;
}

export interface JsonRpcSuccess {
  readonly jsonrpc: typeof JSONRPC_VERSION;
  readonly id: JsonRpcId;
  readonly result: Readonly<Record<string, unknown>>;
}

export interface JsonRpcFailure {
  readonly jsonrpc: typeof JSONRPC_VERSION;
  /** 連 id 都讀不出來時（解析失敗、請求格式錯）規格要求填 null。 */
  readonly id: JsonRpcId | null;
  readonly error: {
    readonly code: number;
    readonly message: string;
    readonly data?: unknown;
  };
}

export type JsonRpcResponse = JsonRpcSuccess | JsonRpcFailure;

/**
 * 協議層的錯誤碼。
 *
 * 前五個是 JSON-RPC 2.0 的標準碼；後兩個由 MCP 2026-07-28 定義，用來回答
 * 「我不會你講的那一版」與「你沒宣告我需要的能力」。工具執行失敗不走這裡——
 * 那是 `isError: true` 的成功回應，模型看得到。
 */
export const ErrorCode = {
  ParseError: -32700,
  InvalidRequest: -32600,
  MethodNotFound: -32601,
  InvalidParams: -32602,
  InternalError: -32603,
  MissingCapability: -32021,
  UnsupportedProtocolVersion: -32022,
} as const;

export function success(id: JsonRpcId, result: Record<string, unknown>): JsonRpcSuccess {
  return { jsonrpc: JSONRPC_VERSION, id, result };
}

export function failure(
  id: JsonRpcId | null,
  code: number,
  message: string,
  data?: unknown,
): JsonRpcFailure {
  return {
    jsonrpc: JSONRPC_VERSION,
    id,
    error: data === undefined ? { code, message } : { code, message, data },
  };
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** 讀得出 id 就回傳，否則回 null——錯誤回應要盡量把 id 帶回去，好讓呼叫端對得上。 */
export function readId(value: unknown): JsonRpcId | null {
  if (!isObject(value)) {
    return null;
  }
  const id = value['id'];
  return typeof id === 'string' || typeof id === 'number' ? id : null;
}

export type Parsed =
  | { readonly kind: 'request'; readonly request: JsonRpcRequest }
  | { readonly kind: 'notification'; readonly notification: JsonRpcNotification }
  | { readonly kind: 'error'; readonly response: JsonRpcFailure };

/**
 * 把一行文字讀成請求或通知。
 *
 * 批次（JSON-RPC 的陣列形式）一律拒絕：MCP 沒有用到它，接受等於多一條沒有測試涵蓋的路。
 */
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
