/**
 * 每一則請求自帶的 `_meta`：講哪一版、會什麼、是誰。
 *
 * 2026-07-28 拿掉了 initialize 握手，這些話改成每則都講一次。Server 因此
 * MUST NOT 從前一則請求推斷任何事——這個模組是那條規則的守門處，讀完就丟，
 * 不留任何跨請求狀態。
 */
import { ErrorCode } from './errors.js';
import { failure, type JsonRpcFailure, type JsonRpcId } from './messages.js';
import { SUPPORTED_PROTOCOL_VERSIONS } from './versions.js';

const PROTOCOL_VERSION_KEY = 'io.modelcontextprotocol/protocolVersion';
const CLIENT_CAPABILITIES_KEY = 'io.modelcontextprotocol/clientCapabilities';
const CLIENT_INFO_KEY = 'io.modelcontextprotocol/clientInfo';

export interface RequestMeta {
  readonly protocolVersion: string;
  readonly clientCapabilities: Readonly<Record<string, unknown>>;
  /** SHOULD 帶，可能沒有。只供 log 與顯示，不得影響任何判斷。 */
  readonly clientInfo?: Readonly<Record<string, unknown>>;
}

export type MetaResult =
  | { readonly ok: true; readonly meta: RequestMeta }
  | { readonly ok: false; readonly response: JsonRpcFailure };

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * 讀出並檢查 `_meta`。
 *
 * 三個出口對應規格在這一格列的三個錯誤碼：欄位缺漏或型別不對是 -32602，版本不支援是
 * -32022，缺少必要能力是 -32021。第三個目前無處可發——本 Server 的工具都不需要 Client
 * 的能力（不做 elicitation、sampling、roots），有了需要它的工具才會用到。
 */
export function readMeta(params: Readonly<Record<string, unknown>> | undefined, id: JsonRpcId): MetaResult {
  const raw = params?.['_meta'];
  if (!isObject(raw)) {
    return { ok: false, response: invalidParams(id, '_meta is required on every request') };
  }

  const version = raw[PROTOCOL_VERSION_KEY];
  if (typeof version !== 'string') {
    return { ok: false, response: invalidParams(id, `_meta["${PROTOCOL_VERSION_KEY}"] is required`) };
  }

  const capabilities = raw[CLIENT_CAPABILITIES_KEY];
  if (!isObject(capabilities)) {
    return { ok: false, response: invalidParams(id, `_meta["${CLIENT_CAPABILITIES_KEY}"] is required`) };
  }

  if (!SUPPORTED_PROTOCOL_VERSIONS.includes(version)) {
    return {
      ok: false,
      response: failure(
        id,
        ErrorCode.UnsupportedProtocolVersion,
        `Unsupported protocol version: ${version}`,
        // 規格要求把自己會的版本列出來，否則 Client 只知道失敗，不知道改送什麼。
        { supportedVersions: [...SUPPORTED_PROTOCOL_VERSIONS] },
      ),
    };
  }

  const clientInfo = raw[CLIENT_INFO_KEY];
  return {
    ok: true,
    meta: isObject(clientInfo)
      ? { protocolVersion: version, clientCapabilities: capabilities, clientInfo }
      : { protocolVersion: version, clientCapabilities: capabilities },
  };
}

function invalidParams(id: JsonRpcId, message: string): JsonRpcFailure {
  return failure(id, ErrorCode.InvalidParams, message);
}
