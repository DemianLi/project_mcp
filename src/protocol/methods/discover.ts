/**
 * `server/discover`：回報支援的版本、能力與身分。
 */
import { CAPABILITIES, SERVER_INFO } from '../../declarations.js';
import { success, type JsonRpcId, type JsonRpcSuccess } from '../messages.js';
import { complete } from '../results.js';
import { SUPPORTED_PROTOCOL_VERSIONS } from '../versions.js';

export function discover(id: JsonRpcId): JsonRpcSuccess {
  return success(
    id,
    complete({
      supportedVersions: [...SUPPORTED_PROTOCOL_VERSIONS],
      capabilities: CAPABILITIES,
      serverInfo: SERVER_INFO,
    }),
  );
}
