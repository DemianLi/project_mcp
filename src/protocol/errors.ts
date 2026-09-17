/**
 * 協議層的錯誤碼。
 *
 * 前五個是 JSON-RPC 2.0 的標準碼；後兩個由 MCP 2026-07-28 定義。工具執行失敗不在這裡——
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
