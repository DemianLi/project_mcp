/**
 * JSON-RPC 2.0 的訊息型別，以及建構回應的兩個函式。
 *
 * 只描述形狀，不含 MCP 語意，也不做解碼——解碼在 parse.ts。
 */
export const JSONRPC_VERSION = '2.0';

export type JsonRpcId = string | number;

export interface JsonRpcRequest {
  readonly jsonrpc: typeof JSONRPC_VERSION;
  readonly id: JsonRpcId;
  readonly method: string;
  readonly params?: Readonly<Record<string, unknown>>;
}

/** 通知沒有 id，因此也不會有回應。 */
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
  /** 連 id 都讀不出來時規格要求填 null。 */
  readonly id: JsonRpcId | null;
  readonly error: {
    readonly code: number;
    readonly message: string;
    readonly data?: unknown;
  };
}

export type JsonRpcResponse = JsonRpcSuccess | JsonRpcFailure;

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
