import { deepStrictEqual, strictEqual } from 'node:assert/strict';
import { describe, it } from 'node:test';
import { ErrorCode } from './jsonrpc.js';
import { readMeta } from './meta.js';
import { PROTOCOL_VERSION } from './versions.js';

const VERSION_KEY = 'io.modelcontextprotocol/protocolVersion';
const CAPABILITIES_KEY = 'io.modelcontextprotocol/clientCapabilities';
const INFO_KEY = 'io.modelcontextprotocol/clientInfo';

function meta(extra: Record<string, unknown> = {}): Record<string, unknown> {
  return { _meta: { [VERSION_KEY]: PROTOCOL_VERSION, [CAPABILITIES_KEY]: {}, ...extra } };
}

describe('readMeta', () => {
  it('accepts the version this Server speaks', () => {
    const result = readMeta(meta(), 1);
    strictEqual(result.ok, true);
    if (!result.ok) return;
    strictEqual(result.meta.protocolVersion, PROTOCOL_VERSION);
  });

  it('carries clientInfo through when it is there', () => {
    const result = readMeta(meta({ [INFO_KEY]: { name: 'inspector' } }), 1);
    strictEqual(result.ok, true);
    if (!result.ok) return;
    deepStrictEqual(result.meta.clientInfo, { name: 'inspector' });
  });

  it('accepts a request without clientInfo, which is only SHOULD', () => {
    const result = readMeta(meta(), 1);
    strictEqual(result.ok, true);
    if (!result.ok) return;
    strictEqual(result.meta.clientInfo, undefined);
  });

  it('rejects a request with no _meta at all', () => {
    const result = readMeta(undefined, 1);
    strictEqual(result.ok, false);
    if (result.ok) return;
    strictEqual(result.response.error.code, ErrorCode.InvalidParams);
  });

  it('rejects a missing protocolVersion as a malformed request, not a version mismatch', () => {
    const result = readMeta({ _meta: { [CAPABILITIES_KEY]: {} } }, 1);
    strictEqual(result.ok, false);
    if (result.ok) return;
    strictEqual(result.response.error.code, ErrorCode.InvalidParams);
  });

  it('rejects missing clientCapabilities', () => {
    const result = readMeta({ _meta: { [VERSION_KEY]: PROTOCOL_VERSION } }, 1);
    strictEqual(result.ok, false);
    if (result.ok) return;
    strictEqual(result.response.error.code, ErrorCode.InvalidParams);
  });

  it('answers a version it does not speak with -32022 and its own list', () => {
    const result = readMeta({ _meta: { [VERSION_KEY]: '2025-11-25', [CAPABILITIES_KEY]: {} } }, 1);
    strictEqual(result.ok, false);
    if (result.ok) return;
    strictEqual(result.response.error.code, ErrorCode.UnsupportedProtocolVersion);
    deepStrictEqual(result.response.error.data, { supportedVersions: [PROTOCOL_VERSION] });
  });
});
